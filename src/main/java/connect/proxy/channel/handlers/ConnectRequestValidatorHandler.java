package connect.proxy.channel.handlers;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;

/**
 * Handler to verify the request is well-formed and the requested host is whitelisted.
 * 
 * @author carlos
 *
 */
public class ConnectRequestValidatorHandler extends SimpleChannelInboundHandler<HttpObject> {
    private static Logger LOG = LogManager.getLogger();

    public static final int DEFAULT_HTTPS_REMOTE_PORT = 443;

    private Set<String> hostWhitelist;

    public ConnectRequestValidatorHandler(Set<String> hostWhitelist) {
        this.hostWhitelist = hostWhitelist;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg) throws Exception {
        // The HttpObject is expected to be a HttpRequest or LastHttpContent.
        // If it is HttpContent, fail because CONNECT should not have content.
        if (!(msg instanceof HttpRequest)) {
            if (!(msg instanceof LastHttpContent)) {
                reject(ctx, msg, HttpResponseStatus.BAD_REQUEST, "Unexpected content");
            }
            return;
        }

        HttpRequest request = (HttpRequest) msg;

        // Only CONNECT is implemented.
        if (request.method() != HttpMethod.CONNECT) {
            reject(ctx, request, HttpResponseStatus.NOT_IMPLEMENTED, "Method NOT implemented");
            return;
        }

        // CONNECT should not have content.
        if (HttpUtil.isContentLengthSet(request) || HttpUtil.isTransferEncodingChunked(request)) {
            reject(ctx, request, HttpResponseStatus.BAD_REQUEST, "Unexpected content headers");
            return;
        }

        // Make sure URI is in authority form (https://tools.ietf.org/html/rfc7231#section-4.3.6)
        URI uri;
        try {
            uri = new URI(null, request.uri(), null, null, null);
        } catch(URISyntaxException e) {
            reject(ctx, request, HttpResponseStatus.BAD_REQUEST, "URI syntax exception");
            return;
        }

        // Check whitelist
        if (!hostWhitelist.contains(uri.getHost())) {
            reject(ctx, request, HttpResponseStatus.FORBIDDEN, "Host NOT whitelisted");
            return;
        }

        // Set explicit port value
        int port = uri.getPort();
        if (port == -1) {
            port = DEFAULT_HTTPS_REMOTE_PORT;
        }

        // Do not dispose the request, update it, and forward it
        ReferenceCountUtil.retain(request);
        request.setUri(uri.getHost() + ":" + port);
        ctx.fireChannelRead(request);
    }

    /**
     * Helper to reject a request. It logs the rejection. Then, responds to client before closing the channel.
     * @param ctx handler context.
     * @param status HTTP response status.
     */
    private void reject(ChannelHandlerContext ctx, HttpObject req, HttpResponseStatus status, String reason) {
        LOG.debug("RejectedRequest: {}. Response: {} - {}. Reason: {}.",
                req.toString(), status.code(), status.reasonPhrase(), reason);
        // NEXT: The response instances could be reused.
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOG.warn("Exception: Channel {} will be closed.", ctx.channel().id(), cause);
        ctx.close();
    }
}
