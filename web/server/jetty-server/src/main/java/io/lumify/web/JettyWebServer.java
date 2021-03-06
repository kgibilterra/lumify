package io.lumify.web;

import io.lumify.core.util.LumifyLogger;
import io.lumify.core.util.LumifyLoggerFactory;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.server.ssl.SslSelectChannelConnector;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.webapp.WebAppContext;

public class JettyWebServer extends WebServer {
    private static final LumifyLogger LOGGER = LumifyLoggerFactory.getLogger(JettyWebServer.class);
    public static final String OPT_DONT_JOIN = "dontjoin";
    private Server server;

    public static void main(String[] args) throws Exception {
        int res = new JettyWebServer().run(args);
        if (res != 0) {
            System.exit(res);
        }
    }

    public JettyWebServer() {
        initFramework = false;
    }

    @Override
    protected Options getOptions() {
        Options options = super.getOptions();

        options.addOption(
                OptionBuilder
                        .withLongOpt(OPT_DONT_JOIN)
                        .withDescription("Don't join the server thread and continue with exit")
                        .create()
        );

        return options;
    }

    @Override
    protected int run(CommandLine cmd) throws Exception {
        SelectChannelConnector httpConnector = new SelectChannelConnector();
        httpConnector.setPort(super.getHttpPort());
        httpConnector.setConfidentialPort(super.getHttpsPort());

        SslContextFactory sslContextFactory = new SslContextFactory();
        sslContextFactory.setKeyStorePath(super.getKeyStorePath());
        sslContextFactory.setKeyStorePassword(super.getKeyStorePassword());
        sslContextFactory.setTrustStore(super.getTrustStorePath());
        sslContextFactory.setTrustStorePassword(super.getTrustStorePassword());
        sslContextFactory.setNeedClientAuth(super.getRequireClientCert());
        SslSelectChannelConnector httpsConnector = new SslSelectChannelConnector(sslContextFactory);
        httpsConnector.setPort(super.getHttpsPort());

        WebAppContext webAppContext = new WebAppContext();
        webAppContext.setContextPath(this.getContextPath());
        webAppContext.setWar(this.getWebAppDir());
        webAppContext.getSessionHandler().getSessionManager().setMaxInactiveInterval(super.getSessionTimeout() * 60);
        LOGGER.info("getMaxInactiveInterval() is %d seconds", webAppContext.getSessionHandler().getSessionManager().getMaxInactiveInterval());

        ContextHandlerCollection contexts = new ContextHandlerCollection();
        contexts.setHandlers(new Handler[]{webAppContext});

        server = new org.eclipse.jetty.server.Server();
        server.setConnectors(new Connector[]{httpConnector, httpsConnector});
        server.setHandler(contexts);

        server.start();
        if (!cmd.hasOption(OPT_DONT_JOIN)) {
            server.join();
        }

        return 0;
    }

    protected org.eclipse.jetty.server.Server getServer() {
        return server;
    }
}
