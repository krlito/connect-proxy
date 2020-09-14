package connect.proxy.channel.handlers;

import static org.junit.Assert.assertEquals;

import java.util.Collections;

import org.junit.Test;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;

public class ConnectRequestValidatorHandlerTest {

    @Test
    public void httpContentInbound_BadRequestResponse() {
        EmbeddedChannel validator = new EmbeddedChannel(
                new ConnectRequestValidatorHandler(Collections.emptySet()));

        HttpContent input = new DefaultHttpContent(Unpooled.buffer());
        validator.writeInbound(input);
        HttpResponse output = validator.readOutbound();
        assertEquals(HttpResponseStatus.BAD_REQUEST, output.status());
    }

    @Test
    public void notConnectRequestInbound_NotImplementedResponse() {
        EmbeddedChannel validator = new EmbeddedChannel(
                new ConnectRequestValidatorHandler(Collections.emptySet()));

        HttpRequest input = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
        validator.writeInbound(input);
        HttpResponse output = validator.readOutbound();
        assertEquals(HttpResponseStatus.NOT_IMPLEMENTED, output.status());
    }

    @Test
    public void connectRequestWithContentLengthInbound_BadRequestResponse() {
        EmbeddedChannel validator = new EmbeddedChannel(
                new ConnectRequestValidatorHandler(Collections.emptySet()));

        HttpRequest input = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.CONNECT, "/");
        HttpUtil.setContentLength(input, 1);
        validator.writeInbound(input);
        HttpResponse output = validator.readOutbound();
        assertEquals(HttpResponseStatus.BAD_REQUEST, output.status());
    }

    @Test
    public void connectRequestWithTransferEncodingInbound_BadRequestResponse() {
        EmbeddedChannel validator = new EmbeddedChannel(
                new ConnectRequestValidatorHandler(Collections.emptySet()));

        HttpRequest input = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.CONNECT, "/");
        HttpUtil.setTransferEncodingChunked(input, true);
        validator.writeInbound(input);
        HttpResponse output = validator.readOutbound();
        assertEquals(HttpResponseStatus.BAD_REQUEST, output.status());
    }

    @Test
    public void connectRequestWithNoUri_BadRequestResponse() {
        EmbeddedChannel validator = new EmbeddedChannel(
                new ConnectRequestValidatorHandler(Collections.emptySet()));

        String aUri = "";
        HttpRequest input = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.CONNECT, aUri);
        validator.writeInbound(input);
        HttpResponse output = validator.readOutbound();
        assertEquals(HttpResponseStatus.BAD_REQUEST, output.status());
    }

    @Test
    public void connectRequestWithHostNotWhitelistedInbound_ForbiddenResponse() {
        EmbeddedChannel validator = new EmbeddedChannel(
                new ConnectRequestValidatorHandler(Collections.emptySet()));

        String aUri = "uri";
        HttpRequest input = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.CONNECT, aUri);
        validator.writeInbound(input);
        HttpResponse output = validator.readOutbound();
        assertEquals(HttpResponseStatus.FORBIDDEN, output.status());
    }

    @Test
    public void validConnectRequestInbound_connectRequestForwarded() {
        String aUri = "uri";
        String expectedUri = aUri + ":" + ConnectRequestValidatorHandler.DEFAULT_HTTPS_REMOTE_PORT;
        EmbeddedChannel validator = new EmbeddedChannel(
                new ConnectRequestValidatorHandler(Collections.singleton(aUri)));

        HttpRequest input = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.CONNECT, aUri);
        validator.writeInbound(input);
        HttpRequest output = validator.readInbound();
        assertEquals(HttpMethod.CONNECT, output.method());
        assertEquals(expectedUri, output.uri());
    }
}
