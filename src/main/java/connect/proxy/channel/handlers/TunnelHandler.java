package connect.proxy.channel.handlers;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.AttributeKey;

/**
 * TunnelHandler reads from the primary channel and forwards (writes) to the coupled channel.
 * 
 * The couple channel MUST be set as an attribute (COUPLE_CHANNEL) in the primary channel.
 * 
 * @author carlos
 *
 */
public class TunnelHandler extends ChannelInboundHandlerAdapter {
    private static Logger LOG = LogManager.getLogger();

    public static final AttributeKey<Channel> COUPLE_CHANNEL = AttributeKey.valueOf("COUPLE_CHANNEL");

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        ctx.read();
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, Object msg) {
        Channel coupleChannel = ctx.channel().attr(COUPLE_CHANNEL).get();
        // Write what I read.
        coupleChannel.writeAndFlush(msg).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) {
                if (future.isSuccess()) {
                    LOG.debug("Channel {} forwarded to {}.", ctx.channel().id(), future.channel().id());
                    // Once I have written, read again.
                    ctx.channel().read();
                } else {
                	System.out.println(future.cause());
                    future.channel().close();
                }
            }
        });
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOG.warn("Exception: Channel {} will be closed.", ctx.channel().id(), cause);
        if (ctx.channel().isActive()) {
            // Try to flush, before closing channel.
            ctx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        LOG.debug("Channel {} is inactive.", ctx.channel().id());
        Channel coupleChannel = ctx.channel().attr(COUPLE_CHANNEL).get();
        if (coupleChannel.isActive()) {
            // This channel has been closed; Closing the coupled channel.
            coupleChannel.close();
        }
    }
}