(ns embedded-traefik.core
  (:require [clojure.java.io :as io]
            [clj-yaml.core :as yaml])
  (:import (java.util.zip GZIPInputStream)
           (org.apache.commons.compress.archivers.tar TarArchiveInputStream)
           (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute PosixFilePermission)
           (java.lang ProcessBuilder$Redirect)
           (java.util ArrayList Collection)))

(defn tar-gz-seq
  "A seq of TarArchiveEntry instances on a TarArchiveInputStream."
  [tis]
  (when-let [item (.getNextTarEntry tis)]
    (cons item (lazy-seq (tar-gz-seq tis)))))

(defn extract [tgz-file out-path]
  (println "Extracting Traefik to" out-path)
  (let [tis (TarArchiveInputStream.
              (GZIPInputStream.
                (io/input-stream tgz-file)))]
    (doseq [entry (tar-gz-seq tis)]
      (let [out-file (io/file (str out-path "/" (.getName entry)))]
        (.mkdirs (.getParentFile out-file))
        (with-open [outf (io/output-stream out-file)]
          (let [bytes (byte-array 32768)]
            (loop [nread (.read tis bytes 0 32768)]
              (when (> nread -1)
                (.write outf bytes 0 nread)
                (recur (.read tis bytes 0 32768))))))))))

(defn extract-traefik [from-resource to-dir posix? binary-name]
  (extract from-resource to-dir)
  (when posix?
    (Files/setPosixFilePermissions (.toPath (File. (str to-dir "/" binary-name))) #{PosixFilePermission/OWNER_READ
                                                                                    PosixFilePermission/OWNER_WRITE
                                                                                    PosixFilePermission/OWNER_EXECUTE
                                                                                    PosixFilePermission/GROUP_EXECUTE
                                                                                    PosixFilePermission/OTHERS_EXECUTE}))
  (.renameTo (File. (str to-dir "/" binary-name)) (File. (str to-dir "/anvil-" binary-name))))

(defonce traefik-process (atom nil))
(defn kill-traefik! []
  (.waitFor (.exec (Runtime/getRuntime) (if (re-find #"Windows" (System/getProperty "os.name"))
                                          "taskkill /F /IM anvil-traefik.exe"
                                          "pkill anvil-traefik"))))

(defonce shutdown-hook (Thread. ^Runnable (fn []
                                            (println "Runtime exiting. Shutting down Traefik.")
                                            (kill-traefik!))))

(defn run-traefik [{:keys [traefik-dir                      ; The directory to extract Traefik into. Default .traefik
                           forward-to                       ; The backend URL to forward traffic to. Default http://127.0.0.1:3030
                           listen-ip                        ; The IP address to listen on. Default 0.0.0.0
                           http-listen-port                 ; The port to listen for plain HTTP connections on. Default 80, can be nil to disable listening for HTTP connections
                           https-listen-port                ; The port to listen for secure HTTPS connections on. Default 443, can be nil to disable listening for HTTP connections
                           redirect-http-to-https?          ; Set to true to redirect all plain HTTP connections to HTTPS. Set to :permanent to make this a permanent redirect. Default true
                           manual-cert-file                 ; Path to a TLS certificate file to use for TLS connections. Default nil
                           manual-cert-key-file             ; Path to a TLS certificate private key file to use for TLS connections. Default nil
                           letsencrypt-domain               ; Domain to request a TLS certificate for. Default nil
                           letsencrypt-email                ; Email address to attach to LetsEncrypt account for renewal reminders. Default nil
                           letsencrypt-staging?             ; Set to true to use the LetsEncrypt staging server instead of live
                           letsencrypt-storage              ; LetsEncrypt certificates will be stored in JSON format at this path. Default <traefik-dir>/letsencrypt-certs.json
                           log-level                        ; Traefik log level. Default :info
                           dashboard-ip                     ; IP to serve the Traefik dashboard on, if enabled. Default 127.0.0.1
                           dashboard-port                   ; Port to serve the Traefik dashboard on. Default nil (disabled)
                           access-log]                       ; Set to true to log requests to stdout. Set to a path to log to a file.
                    :or   {traefik-dir             (.getAbsolutePath (File. ".traefik"))
                           forward-to              "http://127.0.0.1:3030"
                           listen-ip               "0.0.0.0"
                           http-listen-port        80
                           https-listen-port       443
                           redirect-http-to-https? true
                           manual-cert-file        nil
                           manual-cert-key-file    nil
                           letsencrypt-domain      nil
                           letsencrypt-storage     (.getAbsolutePath (File. "letsencrypt-certs.json"))
                           letsencrypt-email       nil
                           letsencrypt-staging?    false
                           log-level               :info
                           dashboard-ip            "127.0.0.1"
                           dashboard-port          nil
                           access-log               false}
                    :as   _options}]
  (let [manual-tls? (and https-listen-port
                         manual-cert-file
                         manual-cert-key-file)
        letsencrypt? (and https-listen-port
                          letsencrypt-domain
                          (not manual-tls?))

        os-name (System/getProperty "os.name")
        os-arch (System/getProperty "os.arch")
        [archive-os-arch binary-name] (cond
                                (re-find #"Windows" os-name)
                                [(str "windows_" os-arch) "traefik.exe"]

                                (re-find #"Linux" os-name)
                                [(str "linux_" (condp = os-arch
                                                 "arm" "armv7"
                                                 "aarch64" "arm64"
                                                 os-arch)) "traefik"]

                                (re-find #"Mac" os-name)
                                ["darwin_amd64" "traefik"]

                                :else
                                (throw (Exception. (str "Unsupported OS: " os-name " (" os-arch ")"))))

        posix? (not (re-find #"Windows" os-name))

        filename (str "traefik_v2.2.0_" archive-os-arch ".tar.gz")

        binary-resource (io/resource filename)

        config-file (File. (str traefik-dir "/traefik.yml"))]

    (when-not binary-resource
      (throw (Exception. (str "Traefik binary not found:" filename))))

    ;; Make sure Traefik isn't already running.
    (kill-traefik!)

    ;; Extract the Traefik executable
    (extract-traefik binary-resource (str traefik-dir "/bin") posix? binary-name)

    ;; If we're managing certificates, make sure the acme config file exists and has the right permissions
    (when (and letsencrypt? letsencrypt-storage)
      (let [storage-file (File. ^String letsencrypt-storage)]
        (when-not (.exists storage-file)
          (spit storage-file ""))
        (when posix?
          (Files/setPosixFilePermissions (.toPath storage-file) #{PosixFilePermission/OWNER_READ
                                                                  PosixFilePermission/OWNER_WRITE}))))

    ;; Generate the Traefik dynamic config
    (spit config-file (yaml/generate-string
                        (merge {:http
                                {:routers (merge (when (or manual-tls? letsencrypt?)
                                                   {"https-route" {:entryPoints ["https"]
                                                                   :rule        "PathPrefix(`/`)"
                                                                   :service     "backend"
                                                                   :tls         (cond manual-tls?
                                                                                      {}
                                                                                      letsencrypt?
                                                                                      {:certResolver "letsEncrypt"
                                                                                       :domains      [{:main letsencrypt-domain}]})}})

                                                 (when http-listen-port
                                                   (if redirect-http-to-https?
                                                     {"redirectToHttpsRoute" {:entryPoints ["http"]
                                                                              :rule        "PathPrefix(`/`)"
                                                                              :middlewares ["redirectToHTTPS"]
                                                                              :service     "noop@internal"}}
                                                     {"http-route" {:entryPoints ["http"]
                                                                    :rule        "PathPrefix(`/`)"
                                                                    :service     "backend"}}))

                                                 (when dashboard-port
                                                   {"dashboard"
                                                    {:entryPoints ["dashboard"]
                                                     :rule        "PathPrefix(`/`)"
                                                     :service     "api@internal"}}))

                                 :middlewares {"redirectToHTTPS"
                                               {"redirectScheme"
                                                {:scheme    :https
                                                 :permanent (= redirect-http-to-https? :permanent)}}}

                                 :services    {"backend"
                                               {:loadBalancer {:servers [{:url forward-to}]}}}}}
                               (when manual-tls?
                                 {:tls
                                  {:stores
                                   {:default
                                    {:defaultCertificate
                                     {:certFile manual-cert-file
                                      :keyFile  manual-cert-key-file}}}}}))))

    ;; Clean up after ourselves when the JVM shuts down.
    (.removeShutdownHook (Runtime/getRuntime) shutdown-hook)
    (.addShutdownHook (Runtime/getRuntime) shutdown-hook)

    (let [traefik-args (concat [(str traefik-dir "/bin/anvil-" binary-name)
                                (str "--log.level=" (name log-level))
                                (str "--providers.file.filename=" (.getAbsolutePath config-file))]

                               (cond (= access-log true)
                                     ["--accesslog=true"]
                                     access-log
                                     [(str "--accesslog.filepath=" access-log)])

                               (when http-listen-port
                                 [(str "--entrypoints.http.address=" listen-ip ":" http-listen-port)])

                               (when dashboard-port
                                 ["--api=true"
                                  "--api.dashboard=true"
                                  (str "--entrypoints.dashboard.address=" dashboard-ip ":" dashboard-port)])

                               (when (or manual-tls? letsencrypt?)
                                 [(str "--entrypoints.https.address=" listen-ip ":" https-listen-port)])

                               (when letsencrypt?
                                 (concat ["--certificatesResolvers.letsEncrypt.acme.tlsChallenge=true"
                                          (str "--certificatesResolvers.letsEncrypt.acme.storage=" letsencrypt-storage)]
                                         (when letsencrypt-email
                                           [(str "--certificatesResolvers.letsEncrypt.acme.email=" letsencrypt-email)])
                                         (when letsencrypt-staging?
                                           ["--certificatesResolvers.letsEncrypt.acme.caServer=https://acme-staging-v02.api.letsencrypt.org/directory"]))))]

      (println traefik-args)
      (reset! traefik-process (-> (doto (ProcessBuilder. #^"[Ljava.lang.String;" (into-array String traefik-args))
                                    (.redirectError ProcessBuilder$Redirect/INHERIT)
                                    (.redirectOutput ProcessBuilder$Redirect/INHERIT))
                                  (.start)))

      (let [process-finished (promise)]
        (future
          (deliver process-finished
                   (.waitFor @traefik-process)))
        process-finished))))


