package com.wolklaw.osrstoolkit;

import java.io.IOException;
import javax.annotation.Nonnull;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;
import okio.GzipSink;
import okio.Okio;

/**
 * Talks to the OSRS Toolkit sync service.
 *
 * Every call is queued on OkHttp's own thread pool rather than executed inline, so nothing here
 * can hold up the client thread or the thread that owns the outbox. Results come back on an
 * OkHttp thread; callers that care about ordering hand the answer to their own executor.
 */
@Slf4j
final class SyncClient
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	/**
	 * What became of one request. The distinction that matters is whether the payload is worth
	 * sending again: a refused payload never will be, so keeping it would block the queue behind
	 * it forever.
	 */
	enum Outcome
	{
		/** Stored. Anything queued for this request can be dropped. */
		DELIVERED,
		/** Nothing arrived — offline, service down, timed out. Keep it and try later. */
		RETRY,
		/**
		 * The token is wrong or unknown. Keep the payload: the fault is in the settings, not in
		 * what was recorded, and someone fixing their token should not find the queue emptied
		 * behind them.
		 */
		UNAUTHORIZED,
		/** The service refused the payload itself and always will: malformed, or too large. */
		REFUSED
	}

	@FunctionalInterface
	interface Callback
	{
		void completed(Outcome outcome);
	}

	private final OkHttpClient httpClient;
	private volatile HttpUrl baseUrl;
	private volatile String token;

	SyncClient(OkHttpClient httpClient)
	{
		this.httpClient = httpClient;
	}

	/**
	 * Point at a service, or at nothing.
	 *
	 * Returns whether the plugin is configured enough to send anything at all — an address that
	 * is blank or not a URL, or a missing token, all mean the same thing to a caller: hold on to
	 * it, there is nowhere to put it yet.
	 */
	boolean configure(String url, String pairingToken)
	{
		HttpUrl parsed = url == null || url.trim().isEmpty() ? null : HttpUrl.parse(url.trim());
		this.baseUrl = parsed;
		this.token = pairingToken == null ? "" : pairingToken.trim();
		return isConfigured();
	}

	boolean isConfigured()
	{
		return baseUrl != null && !token.isEmpty();
	}

	void send(String path, String json, Callback callback)
	{
		HttpUrl url = baseUrl;
		String pairingToken = token;
		if (url == null || pairingToken.isEmpty())
		{
			callback.completed(Outcome.RETRY);
			return;
		}
		Request request = new Request.Builder()
			.url(url.newBuilder().addPathSegments(path).build())
			.header("Authorization", "Bearer " + pairingToken)
			.header("Content-Encoding", "gzip")
			.post(gzip(RequestBody.create(JSON, json)))
			.build();
		httpClient.newCall(request).enqueue(new okhttp3.Callback()
		{
			@Override
			public void onFailure(@Nonnull okhttp3.Call call, @Nonnull IOException ex)
			{
				// Nothing reached the service, so nothing has been recorded. Worth another go.
				log.debug("Unable to reach the sync service", ex);
				callback.completed(Outcome.RETRY);
			}

			@Override
			public void onResponse(@Nonnull okhttp3.Call call, @Nonnull Response response)
			{
				try (Response closed = response)
				{
					callback.completed(outcomeOf(closed.code()));
				}
			}
		});
	}

	/**
	 * What a status code means for the payload that produced it.
	 *
	 * A 4xx is usually the service saying this payload will never work — a body it will not
	 * parse, or one too large. Retrying those forever would wedge the queue behind something
	 * that can never leave it, so they are dropped.
	 *
	 * Two exceptions, both meaning "not this, not now" rather than "not ever". 429 is the
	 * service asking for a pause. 401 and 403 are the token being wrong, which is a settings
	 * problem: throwing away a queue because someone mistyped a token would lose real trades to
	 * fix a typo.
	 */
	private static Outcome outcomeOf(int code)
	{
		if (code >= 200 && code < 300)
		{
			return Outcome.DELIVERED;
		}
		if (code == 401 || code == 403)
		{
			log.debug("Sync service rejected the pairing token ({})", code);
			return Outcome.UNAUTHORIZED;
		}
		if (code == 429)
		{
			return Outcome.RETRY;
		}
		if (code >= 400 && code < 500)
		{
			log.debug("Sync service refused a request with {}", code);
			return Outcome.REFUSED;
		}
		return Outcome.RETRY;
	}

	/**
	 * Compress the body. Event payloads are mostly item names, which compress to roughly a fifth
	 * of their size — worth doing when the service may be someone's home connection.
	 */
	private static RequestBody gzip(RequestBody body)
	{
		return new RequestBody()
		{
			@Override
			public MediaType contentType()
			{
				return body.contentType();
			}

			@Override
			public long contentLength()
			{
				// Not known before compressing, and not worth a second pass to find out.
				return -1;
			}

			@Override
			public void writeTo(@Nonnull BufferedSink sink) throws IOException
			{
				try (BufferedSink gzipSink = Okio.buffer(new GzipSink(sink)))
				{
					body.writeTo(gzipSink);
				}
			}
		};
	}
}
