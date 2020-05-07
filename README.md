# `embedded-traefik`

A clojure library for providing [Traefik](https://containo.us/traefik/) as an embedded service in your app. Provides TLS termination for the [Anvil App Server](https://github.com/anvil-works/anvil-runtime).

### Usage

For now, you will need to `lein install` this project into your local Maven cache directly.

See [`embedded-traefik.core/run-traefik`](https://github.com/anvil-works/embedded-traefik/blob/0acb435729127f3c8c3bfc59fb5f5b504a2a4b2f/src/embedded_traefik/core.clj#L53) for a description of arguments you'll want to provide.
 
See [`anvil.app-server.run`](https://github.com/anvil-works/anvil-runtime/blob/4ccb5c4855d4756c20d5bf834881c538bee77ba6/server/app-server/src/anvil/app_server/run.clj#L397) for example usage

### License

Copyright © 2020 Anvil

Distributed under the MIT Licence. See [LICENSE](LICENSE.md)
