package connect.proxy.channel.handlers;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import java.util.Random;

import org.junit.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;

public class TunnelHandlerTest {
    private static final Random rand = new Random();

    @Test
    public void dataInboundToChannel_dataOutboundToCoupleChannel() {
        EmbeddedChannel in = new EmbeddedChannel(new TunnelHandler());
        EmbeddedChannel out = new EmbeddedChannel();
        in.attr(TunnelHandler.COUPLE_CHANNEL).set(out);

        ByteBuf input = Unpooled.buffer();
        input.writeBytes(getRandomBytes(128));
        in.writeInbound(input);
        ByteBuf output = (ByteBuf) out.readOutbound();
        assertEquals(input, output);

        in.close();
    }

    @Test
    public void channelClosed_coupleChannelClosed() {
        EmbeddedChannel in = new EmbeddedChannel(new TunnelHandler());
        EmbeddedChannel out = new EmbeddedChannel();
        in.attr(TunnelHandler.COUPLE_CHANNEL).set(out);

        assertTrue(out.isActive());
        in.close();
        assertTrue(!out.isActive());
    }

    private byte[] getRandomBytes(int length) {
        byte[] data = new byte[length];
        rand.nextBytes(data);
        return data;
    }
}
