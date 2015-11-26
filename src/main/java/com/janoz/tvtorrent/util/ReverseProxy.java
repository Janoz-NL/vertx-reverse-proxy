package com.janoz.tvtorrent.util;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import com.google.common.base.Strings;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

/**
 * Reverse proxy
 */
public class ReverseProxy extends AbstractVerticle {

    private static Logger LOG = LoggerFactory.getLogger(ReverseProxy.class);

    private Router router;
    private final Map<String, HttpClient>  clientMap = new HashMap<>();
    private final Map<String, String>  prefixMap = new HashMap<>();

    public void start(Future<Void> startFuture)  {
        router = Router.router(vertx);
        vertx.createHttpServer().requestHandler(router::accept).listen(80, result -> {
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
        String label = request.uri().substring(1,index);
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

    private String buildUri(String path, String query) {
        if (Strings.isNullOrEmpty(query)) {
            return path;
        }
        return path + '?' + query;
    }

    public void addClient(String prefix, HttpClientOptions options) {
        Objects.requireNonNull(vertx,"Verticle should be started.");
        clientMap.put(prefix, vertx.createHttpClient(options));
        router.route("/" + prefix + "*").handler(this::handleProxyRequest);
    }

    public void addClient(String prefix, HttpClientOptions options, String clientPrefix) {
        prefixMap.put(prefix, clientPrefix);
        addClient(prefix,options);
    }
}

