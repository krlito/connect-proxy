package connect.proxy.channel.handlers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;

import javax.net.ssl.SSLException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoop;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoop;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

/**
 * Handler for the CONNECT requests on the channel server side.
 * 
 * Validation for the incoming request should have been done in a previous handler. It assumes the
 * request URI is in authority form.
 * 
 * It tries to open a new channel to the remote server. If successful, the incoming channel and the
 * new channel are coupled together using TunnelHandlers.
 * 
 * @author carlos
 *
 */
public class ConnectRequestHandler extends SimpleChannelInboundHandler<HttpRequest> {
    private static Logger LOG = LogManager.getLogger();

    private static final FullHttpResponse RESPONSE_OK =
        new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);

    private static final FullHttpResponse RESPONSE_SERVICE_UNAVAILABLE =
        new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.SERVICE_UNAVAILABLE);

    private final NioEventLoop preferredNioEventLoop;
    private List<ChannelHandler> mandatoryHandlers;

    /**
     * Constructor.
     * @param mandatoryHandlers handlers to be kept when the pipeline is re-arranged for tunneling.
     */
    public ConnectRequestHandler(List<ChannelHandler> mandatoryHandlers) {
        this(mandatoryHandlers, null);
    }

    /**
     * Constructor.
     * @param mandatoryHandlers handlers to be kept when the pipeline is re-arranged for tunneling.
     * @param preferredNioEventLoop NioEventLoop to be used when connecting to the remote host.
     *        If it is null, the NioEventLoop of the incoming channel will be used.
     */
    public ConnectRequestHandler(List<ChannelHandler> mandatoryHandlers,
            NioEventLoop preferredNioEventLoop) {
        this.mandatoryHandlers = new ArrayList<>(mandatoryHandlers);
        this.preferredNioEventLoop = preferredNioEventLoop;
    }


    @Override
    protected void channelRead0(ChannelHandlerContext ctx, HttpRequest msg) throws Exception {
        // Do not read automatically anymore. We might be switching to a TunnelHandler.
        ctx.channel().config().setAutoRead(false);

        // URI is expected in authority form (https://tools.ietf.org/html/rfc7231#section-4.3.6)
        // Validation should has been done in a previous handler (e.g. ConnectRequestValidatorHandler)
        String[] uriParts = msg.uri().split(":");
        String host = uriParts[0];
        int port = Integer.parseInt(uriParts[1]);
        tunnel(host, port, ctx);
    }

    /**
     * Create a connection to remote host and upgrade incoming channel to tunnel.
     * @param host target host
     * @param port target port of the host
     * @param ctx Context of the incoming pre-existing channel. Its configuration will be reused.
     * @throws SSLException
     */
    private void tunnel(String host, int port, ChannelHandlerContext ctx) throws SSLException  {
        Channel inChannel = ctx.channel();
        ChannelFuture connectFuture = connect(host, port, ctx);

        connectFuture.addListener(future -> {
            // If connection to remote host is established.
            if (future.isSuccess()) {
                LOG.debug("Tunneled channel {} to {}:{}.", inChannel.id(), host, port);

                ChannelFuture okResponseFuture = ctx.writeAndFlush(RESPONSE_OK);

                // Start reading from incoming channel once OK response has been sent.
                okResponseFuture.addListener(f -> {
                	setupTunnelPipeline(inChannel, connectFuture.channel());
                	inChannel.read();
                });

            } else {
                LOG.warn("Failed to tunnel channel {} to {}:{}.", inChannel.id(), host, port,
                    future.cause());
                ctx.writeAndFlush(RESPONSE_SERVICE_UNAVAILABLE)
                    .addListener(ChannelFutureListener.CLOSE);
            }
        });
    }

    /**
     * Connect to a host
     * @param host target host
     * @param port target port of the host
     * @param ctx Context of the incoming pre-existing channel. Its configuration will be reused.
     * @return A future for the result of the connection.
     * @throws SSLException
     */
    private ChannelFuture connect(String host, int port, ChannelHandlerContext ctx) throws SSLException {
        EventLoop evLoop;

        if (preferredNioEventLoop != null) {
            evLoop = preferredNioEventLoop;
        } else {
            evLoop =  ctx.channel().eventLoop();
        }

        Bootstrap b = new Bootstrap()
            .group(evLoop)
            .channel(NioSocketChannel.class)
            .option(ChannelOption.SO_KEEPALIVE, true)
            .option(ChannelOption.AUTO_READ, false)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                public void initChannel(SocketChannel ch) throws Exception {
                    ch.pipeline()
                        .addLast(new TunnelHandler());
                }
            });

        return b.connect(host, port);
    }

    /**
     * Setup channels and pipeline configuration for tunneling.
     * @param inChannel
     * @param outChannel
     */
    private void setupTunnelPipeline(Channel inChannel, Channel outChannel) {
        // Set COUPLE_CHANNEL attribute. It is used by the TunnelHandler to know where to
        // redirect channel incoming data. The new outgoing client channel data is to be
        // redirected to the incoming channel, and viceversa.
        outChannel.attr(TunnelHandler.COUPLE_CHANNEL).set(inChannel);
        inChannel.attr(TunnelHandler.COUPLE_CHANNEL).set(outChannel);

        // Add tunnel handler.
        ChannelHandler tunnelHandler = new TunnelHandler();
        inChannel.pipeline().addLast(tunnelHandler);
        mandatoryHandlers.add(tunnelHandler);

        // For incoming channel, remove handlers except for the mandatory handlers.
        ChannelPipeline pipeline = inChannel.pipeline();
        pipeline.forEach((Entry<String, ChannelHandler> entry) -> {
            if (!mandatoryHandlers.contains(entry.getValue())) {
                pipeline.remove(entry.getValue());
            }
        });
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOG.warn("Exception: Channel {} will be closed.", ctx.channel().id(), cause);
        ctx.close();
    }
}