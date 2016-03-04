package com.janoz.tvtorrent.util;

import com.google.common.base.Strings;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.http.*;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.net.JksOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import nl.robal.alpha.SslRedirector;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;

/**
 * Asynchronous Reverse proxy, powered by Vert.x
 *
 * @author Gijs de Vries aka Janoz
 */
public class ReverseProxy extends AbstractVerticle {
    private static final String CONFIG_PROPERTIES = "config.properties";

    private static final String PROXY_PORT = "proxy.port";

    public static final String SSL_REDIR_PORT_ORIGIN = "sslredirector.port.origin";
    public static final String SSL_REDIR_PORT_DEST = "sslredirector.port.destination";
    public static final String SSL_REDIR_EXTERNALHOST = "sslredirector.externalhostname";

    private static Logger LOG = LoggerFactory.getLogger(ReverseProxy.class);

    private Router router;
    private final Map<String, HttpClient> clientMap = new HashMap<>();
    private final Map<String, String> prefixMap = new HashMap<>();

    public void start(Future<Void> startFuture) throws IOException {
        router = Router.router(vertx);

        HttpServerOptions httpServerOptions = new HttpServerOptions();
        if (System.getenv("jkspath") != null && System.getenv("jkspass") != null) {
            System.out.println("[jks] using keystore=[" + System.getenv().get("jkspath") + "]");
            httpServerOptions.setSsl(true).setKeyStoreOptions(
                    new JksOptions().setPath(System.getenv("jkspath")).setPassword(System.getenv("jkspass"))
            );
        } else {
            System.out.println("[jks] no keystore provided");
        }

        Properties config = new Properties();
        config.load(new FileInputStream(CONFIG_PROPERTIES));

        int port = 8080;
        try {
            port = Integer.parseInt(config.getProperty(PROXY_PORT));
        } catch (NumberFormatException e) {
            LOG.error("No, or Malformed port number provided, starting with default port " + port);
        }
        for (Map.Entry<Object, Object> client : config.entrySet()) {
            String key = (String) client.getKey();
            if (!Arrays.asList(PROXY_PORT, SSL_REDIR_PORT_ORIGIN, SSL_REDIR_PORT_DEST, SSL_REDIR_EXTERNALHOST).contains(key) &&
                    !Strings.isNullOrEmpty((String) client.getValue())) {
                String[] properties = ((String) client.getValue()).split(",");
                if ("rootredirect".equals(key)) {
                    router.route("/").handler(routingContext -> {
                        final HttpServerRequest request = routingContext.request();
                        final HttpServerResponse response = request.response();
                        response.headers().add("Location", "/" + client.getValue());
                        response.setStatusCode(303);
                        response.end();
                    });
                } else if (properties.length == 3) {
                    addClient(key, defaultClientOptions().setDefaultHost(properties[0]).setDefaultPort(Integer.parseInt(properties[1])), properties[2]);
                } else if (properties.length == 2) {
                    addClient(key, defaultClientOptions().setDefaultHost(properties[0]).setDefaultPort(Integer.parseInt(properties[1])));
                }
            } else
                addClient(key, defaultClientOptions().setDefaultHost("localhost").setDefaultPort(80));
        }

        List<String> parameters = Arrays.asList(config.getProperty(SSL_REDIR_PORT_ORIGIN), config.getProperty(SSL_REDIR_PORT_DEST), config.getProperty(SSL_REDIR_EXTERNALHOST));
        if (!parameters.contains(null)) {
            DeploymentOptions options = new DeploymentOptions().setConfig(new JsonObject()
                    .put(SSL_REDIR_PORT_ORIGIN, config.getProperty(SSL_REDIR_PORT_ORIGIN))
                    .put(SSL_REDIR_PORT_DEST, config.getProperty(SSL_REDIR_PORT_DEST))
                    .put(SSL_REDIR_EXTERNALHOST, config.getProperty(SSL_REDIR_EXTERNALHOST)));
            vertx.deployVerticle(new SslRedirector(), options, res -> {
                if (res.failed()) {
                    res.cause().printStackTrace();
                }
            });
        } else {
            LOG.info("insufficient parameters provided for " + SslRedirector.class.getName());
        }

        vertx.createHttpServer(httpServerOptions).requestHandler(router::accept).listen(port, result -> {
                    if (result.succeeded()) {
                        startFuture.complete();
                    } else {
                        result.cause().printStackTrace();
                        startFuture.fail(result.cause());
                    }
                }
        );
    }

    private void handleProxyRequest(RoutingContext context) {
        final HttpServerRequest request = context.request();
        final HttpServerResponse response = request.response();
        int index = request.path().indexOf('/', 1);
        if (index == -1) {
            //force the uri to a 'folder'
            response.headers().add("Location", ReverseProxy.this.buildUri(request.path() + '/', request.query()));
            response.setStatusCode(303);
            response.end();
            return;
        }
        String uri = request.uri().substring(index);
        String label = request.uri().substring(1, index);
        if (prefixMap.containsKey(label)) {
            uri = "/" + prefixMap.get(label) + uri;
        }
        final HttpClientRequest clientRequest = clientMap.get(label).request(request.method(), uri, clientResponse -> {
            response.setStatusCode(clientResponse.statusCode());
            response.setStatusMessage(clientResponse.statusMessage());
            response.headers().setAll(clientResponse.headers());
            response.setChunked(true);
            clientResponse.handler(data -> response.write(data));
            clientResponse.endHandler((aVoid) -> response.end());
        });
        clientRequest.headers().addAll(request.headers());
        clientRequest.setChunked(true);
        clientRequest.exceptionHandler(cause -> {
            LOG.error("Problem with requesting proxied resource '" + request.uri() + "'.", cause);
            response.setStatusCode(500).end(cause.getMessage());
        });
        request.handler(data -> clientRequest.write(data));
        request.endHandler((aVoid) -> clientRequest.end());
    }

    private HttpClientOptions defaultClientOptions() {
        HttpClientOptions httpClientOptions = new HttpClientOptions();
        httpClientOptions.setKeepAlive(true).setMaxPoolSize(10000);
        return httpClientOptions;
    }

    static String buildUri(String path, String query) {
        if (Strings.isNullOrEmpty(query)) {
            return path;
        }
        return path + '?' + query;
    }

    public void addClient(String prefix, HttpClientOptions options) {
        Objects.requireNonNull(vertx, "Verticle should be started.");
        clientMap.put(prefix, vertx.createHttpClient(options));
        router.route("/" + prefix + "*").handler(this::handleProxyRequest);
    }

    public void addClient(String prefix, HttpClientOptions options, String clientPrefix) {
        prefixMap.put(prefix, clientPrefix);
        addClient(prefix, options);
    }
}
