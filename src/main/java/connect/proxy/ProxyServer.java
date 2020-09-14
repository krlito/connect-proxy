package connect.proxy;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import javax.net.ssl.SSLException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import connect.proxy.channel.handlers.ConnectRequestHandler;
import connect.proxy.channel.handlers.ConnectRequestValidatorHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.util.SelfSignedCertificate;

/**
 * HTTPS CONNECT proxy server.
 * 
 * @author carlos
 *
 */
public class ProxyServer {
    private static Logger LOG = LogManager.getLogger();
    private static int DEFAULT_SO_BACKLOG = 128;

    private int port;
    private Set<String> hostWhitelist;
    private Channel serverChannel;
    EventLoopGroup acceptorEvLoopGroup;
    EventLoopGroup workEvLoopGroup;

    public ProxyServer(int port, String[] hostWhitelist) {
        this.port = port;
        this.hostWhitelist = new HashSet<>(Arrays.asList(hostWhitelist));
    }

    /**
     * Start the proxy server. Blocks the current thread.
     * @throws SSLException
     * @throws InterruptedException
     */
    public void start() throws Exception {
        // This is a basic self-signed SSL/TLS implementation.
        // NEXT: Strengthen secure layer.
        SelfSignedCertificate ssc = new SelfSignedCertificate();
        SslContext sslContext = SslContextBuilder
            .forServer(ssc.certificate(), ssc.privateKey())
            .build();

        // A NioEventLoopGroup is a group of event loops. Each loop is executed in a different
        // thread. By default, NioEventLoopGroup size is 2 times the number of processors.
        acceptorEvLoopGroup = new NioEventLoopGroup(1);
        workEvLoopGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(acceptorEvLoopGroup, workEvLoopGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, DEFAULT_SO_BACKLOG)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                     @Override
                     public void initChannel(SocketChannel ch) throws Exception {
                         // PIPELINE: SSL -> HTTP -> CONNECT Validator -> CONNECT handler
                         SslHandler sslHandler = sslContext.newHandler(ch.alloc());
                         ch.pipeline()
                             .addLast(sslHandler)
                             .addLast(new HttpServerCodec())
                             .addLast(new ConnectRequestValidatorHandler(hostWhitelist))
                             .addLast(new ConnectRequestHandler(Arrays.asList(sslHandler)));
                     }
                 });

            serverChannel = bootstrap.bind(port).sync().channel();
            serverChannel.closeFuture().addListener((future) -> {
                acceptorEvLoopGroup.shutdownGracefully();
                workEvLoopGroup.shutdownGracefully();
            });

            LOG.info("Server started. Port = {}.", port);
        } catch (Throwable t) {
            acceptorEvLoopGroup.shutdownGracefully();
            workEvLoopGroup.shutdownGracefully();

            throw t;
        }
    }

    public void close() throws InterruptedException {
        if (serverChannel != null && serverChannel.isOpen()) {
            serverChannel.close();
        }
    }

    public void waitForClose() throws InterruptedException {
        if (serverChannel != null && serverChannel.isOpen()) {
            serverChannel.closeFuture().sync();
        }
    }
}
