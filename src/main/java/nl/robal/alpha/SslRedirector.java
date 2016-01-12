package nl.robal.alpha;

import com.google.common.base.Strings;
import com.janoz.tvtorrent.util.ReverseProxy;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.Router;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Date;

/**
 * This verticle ensures you're visiting the SSL enabled version of your website by redirecting your clients.
 *
 * @author Robert S. van Alphen
 * @since 1/11/16.
 */
public class SslRedirector extends AbstractVerticle {
    private static Logger LOG = LoggerFactory.getLogger(SslRedirector.class);


    public void start(Future<Void> startFuture) throws IOException {
        Router router = Router.router(vertx);
        LOG.debug("starting SslRedirector");

        int originport = 0;
        int destinationport = 0;
        String externalHostname = null;
        String externalHostnameString = null;
        try {
            originport = Integer.parseInt(config().getString(ReverseProxy.SSL_REDIR_PORT_ORIGIN));
            destinationport = Integer.parseInt(config().getString(ReverseProxy.SSL_REDIR_PORT_DEST));
            externalHostnameString = config().getString(ReverseProxy.SSL_REDIR_EXTERNALHOST);
            // I'm not parsing urls myself haha.. too lazy :)
            URL host = new URL(externalHostnameString);
            externalHostname = host.getHost();
        } catch (MalformedURLException e) {
            LOG.error("Error parsing host url: " + externalHostnameString);
            startFuture.fail(e.getCause());
        } catch (NumberFormatException e) {
            LOG.error("Error parsing port number!");
            startFuture.fail(e.getCause());
        }


        final int finalDestinationport = destinationport;
        final String finalExternalHostname = externalHostname;
        router.route("/*").handler(routingContext -> {
            final HttpServerRequest request = routingContext.request();
            final HttpServerResponse response = request.response();
            String path = request.path();
            String query = request.query();
            String tail = (Strings.isNullOrEmpty(query)) ? path : path + '?' + query;
            String newUri = "https://" + finalExternalHostname + ":" + finalDestinationport + (!tail.startsWith("/") ? '/' : "") + tail;
            response.headers().add("Location", newUri);
            response.setStatusCode(303);
            response.end();
        });
        vertx.createHttpServer(new HttpServerOptions()).requestHandler(router::accept).listen(originport, result -> {
                    if (result.succeeded()) {
                        LOG.info("[" + Thread.currentThread().getName() + "-" + new Date() + "]:" + " started " + this.getClass().getName());
                        startFuture.complete();
                    } else {
                        LOG.info("[" + Thread.currentThread().getName() + "-" + new Date() + "]:" + " failed to start " + this.getClass().getName());
                        result.cause().printStackTrace();
                        startFuture.fail(result.cause());
                    }
                }
        );
    }

}