(ns embedded-traefik.core
  (:require [clojure.java.io :as io]
            [org.httpkit.client :as http]
            [clojure.data.json :as json])
  (:import (java.util.zip GZIPInputStream)
           (org.apache.commons.compress.archivers.tar TarArchiveInputStream)
           (java.io BufferedReader File)
           (java.nio.file Files)
           (java.nio.file.attribute PosixFilePermission)
           (java.lang ProcessBuilder$Redirect)))

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

(defonce traefik-process (atom nil))
(defn kill-traefik! []
  (.waitFor (.exec (Runtime/getRuntime) (if (re-find #"Windows" (System/getProperty "os.name"))
                                          "taskkill /F /IM anvil-traefik.exe"
                                          "pkill anvil-traefik"))))

(defonce shutdown-hook (Thread. ^Runnable (fn []
                                            (println "Runtime exiting. Shutting down Traefik.")
                                            (kill-traefik!))))


(defn run-traefik [{:keys [traefik-dir hostname management-address forward-to]
                    {:keys [email staging? storage] tls-port :port :as tls} :tls
                    :or {traefik-dir (.getAbsolutePath (File. "_traefik"))
                         management-address "127.0.0.1:8080"
                         forward-to "127.0.0.1:3030"
                         tls nil}
                    :as options}]
  (let [;tls {:email "standalone-app-cert@iandavies.org"
        ;     :staging? true
        ;     :storage (str traefik-dir "/acme.json")}

        os (System/getProperty "os.name")
        [os-arch binary-name] (cond
                                (re-find #"Windows" os)
                                ["windows_amd64" "traefik.exe"]

                                (re-find #"Linux" os)
                                ["linux_amd64" "traefik"]

                                (re-find #"Mac" os)
                                ["darwin_amd64" "traefik"]

                                :else
                                (throw (Exception. (str "Unsupported OS: " os))))

        filename (str "traefik_v2.2.0_" os-arch ".tar.gz")

        binary-resource (io/resource filename)]
    (when-not binary-resource
      (throw (Exception. (str "Traefik binary not found:" filename))))

    (kill-traefik!)
    (extract binary-resource (str traefik-dir "/bin"))
    (when-not (re-find #"Windows" os)
      (Files/setPosixFilePermissions (.toPath (File. (str traefik-dir "/bin/" binary-name))) #{PosixFilePermission/OWNER_EXECUTE PosixFilePermission/GROUP_EXECUTE PosixFilePermission/OTHERS_EXECUTE}))
    (.renameTo (File. (str traefik-dir "/bin/" binary-name)) (File. (str traefik-dir "/bin/anvil-" binary-name)))
    (when tls
      (let [storage-file (File. ^String storage)]
        (when-not (.exists storage-file)
          (spit storage-file "")))) ; Make sure storage file exists
    (.removeShutdownHook (Runtime/getRuntime) shutdown-hook)
    (.addShutdownHook (Runtime/getRuntime) shutdown-hook)
    (reset! traefik-process (doto (ProcessBuilder. (concat [(str traefik-dir "/bin/anvil-" binary-name)
                                                            "--log.level=debug"
                                                            "--api.insecure=true"
                                                            "--api.dashboard=true"
                                                            "--providers.rest.insecure=true"
                                                            ;"--entrypoints.http.address=:80"
                                                            (str "--entrypoints.traefik.address=" management-address)]
                                                           (if tls
                                                             (concat ["--certificatesResolvers.letsEncrypt.acme.tlsChallenge=true"
                                                                      (str "--entrypoints.https.address=:" tls-port)
                                                                      (str "--certificatesResolvers.letsEncrypt.acme.email=" email)
                                                                      (str "--certificatesResolvers.letsEncrypt.acme.storage=" storage)]
                                                                     (when staging?
                                                                       ["--certificatesResolvers.letsEncrypt.acme.caServer=https://acme-staging-v02.api.letsencrypt.org/directory"])))))
                              (.redirectError ProcessBuilder$Redirect/INHERIT)
                              (.start)))
    
    (future
      (with-open [reader (io/reader (.getInputStream @traefik-process))]
        (loop []
          (when-let [line (.readLine ^BufferedReader reader)]
            (println "[Traefik] " line)
            (recur)))))

    ; Give Traefik a few seconds to start. Query the API to confirm that it's up and running.
    (loop [remaining-seconds 5]
      (Thread/sleep 1000)
      (when-not (try
                  (let [resp @(http/get (str "http://" management-address "/api/rawdata"))]
                    (and (= (:status resp) 200)
                         (json/read-str (:body resp))))
                  (catch Exception e nil))
        (if (pos? remaining-seconds)
          (recur (dec remaining-seconds))
          (throw (Exception. "Traefik failed to start within a reasonable time.")))))

    (let [resp @(http/put (str "http://" management-address "/api/providers/rest")
                          {:body (json/write-str {:http {:routers  {"anvil-app" (merge {:entryPoints (if tls ["http"] ["https"])
                                                                                        :rule        (str "Host(`" hostname "`)")
                                                                                        :service     "anvil-runtime"}
                                                                                       (when tls
                                                                                         {:tls {:certResolver "letsEncrypt"}}))}
                                                         :services {"anvil-runtime" {:loadBalancer {:servers [{:url forward-to}]}}}}})})]
      (when-not (= (:status resp) 200)
        (throw (Exception. "Could not configure Traefik:" (:body resp)))))))


