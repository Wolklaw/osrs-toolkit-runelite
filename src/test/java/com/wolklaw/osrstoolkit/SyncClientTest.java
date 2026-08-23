package com.wolklaw.osrstoolkit;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The verb each path is called with.
 *
 * Worth a test of its own because getting it wrong fails quietly: the service answers a POST to
 * a PUT-only path with 405, which the client reads as "never send this again", so live state
 * stops arriving while the heartbeat on the same client keeps saying the connection is fine.
 */
public class SyncClientTest
{
	@Test
	public void collectedThingsArePostedAndTheOneCopyIsPut() throws Exception
	{
		Recorder recorder = new Recorder();
		SyncClient client = new SyncClient(
			new OkHttpClient.Builder().addInterceptor(recorder).build()
		);
		client.configure("https://sync.example/", "token");

		assertEquals("POST", recorder.methodOf(client, "v1/events", false));
		assertEquals("POST", recorder.methodOf(client, "v1/heartbeat", false));
		assertEquals("PUT", recorder.methodOf(client, "v1/state/offers", true));
		assertEquals("PUT", recorder.methodOf(client, "v1/state/screen", true));
	}

	@Test
	public void everyCallCarriesTheTokenAndOurOwnName() throws Exception
	{
		Recorder recorder = new Recorder();
		SyncClient client = new SyncClient(
			new OkHttpClient.Builder().addInterceptor(recorder).build()
		);
		client.configure("https://sync.example/", "token");

		recorder.methodOf(client, "v1/state/screen", true);
		Request sent = recorder.sent.get(0);

		assertEquals("Bearer token", sent.header("Authorization"));
		assertEquals("gzip", sent.header("Content-Encoding"));
		assertEquals("https://sync.example/v1/state/screen", sent.url().toString());
	}

	/** Answers every request with 200 without leaving the process, and keeps what it was asked. */
	private static final class Recorder implements Interceptor
	{
		private final List<Request> sent = new ArrayList<>();

		@Override
		public Response intercept(Chain chain) throws IOException
		{
			Request request = chain.request();
			synchronized (sent)
			{
				sent.add(request);
			}
			return new Response.Builder()
				.request(request)
				.protocol(Protocol.HTTP_1_1)
				.code(200)
				.message("OK")
				.body(ResponseBody.create(MediaType.parse("application/json"), "{}"))
				.build();
		}

		String methodOf(SyncClient client, String path, boolean replace) throws InterruptedException
		{
			int before = sent.size();
			CountDownLatch done = new CountDownLatch(1);
			if (replace)
			{
				client.replace(path, "{}", outcome -> done.countDown());
			}
			else
			{
				client.send(path, "{}", outcome -> done.countDown());
			}
			done.await(10, TimeUnit.SECONDS);
			return sent.get(before).method();
		}
	}
}
