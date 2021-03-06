/*
 * Copyright 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.openbike.android.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.zip.GZIPInputStream;

import org.apache.http.Header;
import org.apache.http.HeaderElement;
import org.apache.http.HttpEntity;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.HttpResponse;
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.client.HttpClient;
import org.apache.http.entity.HttpEntityWrapper;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.apache.http.params.HttpProtocolParams;
import org.apache.http.protocol.HttpContext;

import android.app.IntentService;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.preference.PreferenceManager;
import android.text.format.DateUtils;
import fr.openbike.android.io.RemoteExecutor;
import fr.openbike.android.io.RemoteNetworksHandler;
import fr.openbike.android.io.RemoteStationsSyncHandler;
import fr.openbike.android.io.RemoteStationsUpdateHandler;
import fr.openbike.android.io.JSONHandler.HandlerException;
import fr.openbike.android.model.Network;
import fr.openbike.android.ui.AbstractPreferencesActivity;

/**
 * Background {@link Service} that synchronizes data living in
 * {@link ScheduleProvider}. Reads data from both local {@link Resources} and
 * from remote sources, such as a spreadsheet.
 */
public class SyncService extends IntentService {

	public static final String EXTRA_STATUS_RECEIVER = "fr.openbike.android.extra.STATUS_RECEIVER";
	public static final String EXTRA_RESULT = "extra_result";
	public static final String ACTION_SYNC = "action_sync";
	public static final String ACTION_UPDATE = "action_update";
	public static final String ACTION_CHOOSE_NETWORK = "action_choose_network";
	public static final String ACTION_RESULT_NETWORK = "action_result_network";
	public static final String ACTION_RESULT_SYNC = "action_result_sync";
	public static final String ACTION_START_UPDATE = "action_start_update";
	public static final String ACTION_START_NETWORK = "action_start_network";
	public static final String NETWORKS_URL = "http://openbikeserver.appspot.com/v2/networks";

	public static final int STATUS_ERROR = 0x2;
	public static final int STATUS_SYNC_STATIONS = 0x1;
	public static final int STATUS_SYNC_STATIONS_FINISHED = 0x3;
	public static final int STATUS_SYNC_NETWORKS = 0x4;
	public static final int STATUS_SYNC_NETWORKS_FINISHED = 0x5;
	public static final int STATUS_UPDATE_STATIONS = 0x6;
	public static final int STATUS_UPDATE_STATIONS_FINISHED = 0x7;

	private static final int SECOND_IN_MILLIS = (int) DateUtils.SECOND_IN_MILLIS;

	private static final String HEADER_ACCEPT_ENCODING = "Accept-Encoding";
	private static final String ENCODING_GZIP = "gzip";

	private RemoteExecutor mRemoteExecutor;
	private SharedPreferences mPreferences;

	/**
	 * @param name
	 */
	public SyncService() {
		super("OpenBike Sync service");
	}

	@Override
	public void onCreate() {
		super.onCreate();

		final HttpClient httpClient = getHttpClient(this);
		mRemoteExecutor = new RemoteExecutor(httpClient);
		mPreferences = PreferenceManager.getDefaultSharedPreferences(this);
	}

	@SuppressWarnings("unchecked")
	@Override
	protected void onHandleIntent(Intent intent) {
		NetworkInfo activeNetwork = ((ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE))
				.getActiveNetworkInfo();
		final ResultReceiver receiver = intent
				.getParcelableExtra(EXTRA_STATUS_RECEIVER);
		Bundle bundle = new Bundle();
		int status = STATUS_ERROR;
		if (activeNetwork == null || !activeNetwork.isConnectedOrConnecting()) {
			bundle = new Bundle();
			bundle.putString(Intent.EXTRA_TEXT, "No network connectivity");
			receiver.send(STATUS_ERROR, bundle);
			return;
		}
		String action = intent.getAction();
		try {
			if (ACTION_SYNC.equals(action)) {
				if (receiver != null) {
					receiver.send(STATUS_SYNC_STATIONS, Bundle.EMPTY);
				}
				SharedPreferences prefs = PreferenceManager
						.getDefaultSharedPreferences(this);
				Object result = mRemoteExecutor.executeGet(mPreferences
						.getString(
								AbstractPreferencesActivity.UPDATE_SERVER_URL,
								"")
						+ "/v2/stations", new RemoteStationsSyncHandler(prefs
						.getLong(AbstractPreferencesActivity.STATIONS_VERSION,
								0)), this);
				if (result == null) {
					// Need stations update
					action = ACTION_UPDATE;
				} else {
					String message = (String) result;
					status = STATUS_SYNC_STATIONS_FINISHED;
					if (!"".equals(message)) {
						bundle.putString(EXTRA_RESULT, message);
					}
					prefs.edit().putLong(
							AbstractPreferencesActivity.LAST_UPDATE,
							System.currentTimeMillis()).commit();
				}
			}
			if (ACTION_UPDATE.equals(action)) {
				if (receiver != null) {
					receiver.send(STATUS_UPDATE_STATIONS, Bundle.EMPTY);
				}
				SharedPreferences prefs = PreferenceManager
						.getDefaultSharedPreferences(this);
				Object result = mRemoteExecutor.executeGet(mPreferences
						.getString(
								AbstractPreferencesActivity.UPDATE_SERVER_URL,
								"")
						+ "/v2/stations/list",
						new RemoteStationsUpdateHandler(), this);
				status = STATUS_UPDATE_STATIONS_FINISHED;
				if (result != null) {
					bundle.putString(EXTRA_RESULT, (String) result);
				}
				prefs.edit().putLong(AbstractPreferencesActivity.LAST_UPDATE,
						System.currentTimeMillis()).commit();
			}
			if (ACTION_CHOOSE_NETWORK.equals(action)) {
				if (receiver != null) {
					receiver.send(STATUS_SYNC_NETWORKS, Bundle.EMPTY);
					bundle = new Bundle();
					bundle.putParcelableArrayList(EXTRA_RESULT,
							(ArrayList<Network>) mRemoteExecutor.executeGet(
									NETWORKS_URL, new RemoteNetworksHandler(),
									this));
					status = STATUS_SYNC_NETWORKS_FINISHED;
				}
			}
			if (receiver != null) {
				receiver.send(status, bundle);
			}
		} catch (HandlerException e) {
			if (receiver != null) {
				// Pass back error to surface listener
				bundle = new Bundle();
				bundle.putString(Intent.EXTRA_TEXT, e.toString());
				receiver.send(STATUS_ERROR, bundle);
			}
		} catch (Exception e) {
			if (receiver != null) {
				// Pass back error to surface listener
				e.printStackTrace();
				bundle = new Bundle();
				bundle.putString(Intent.EXTRA_TEXT, "Une erreur est survenue. Veuillez réessayer.");
				receiver.send(STATUS_ERROR, bundle);
			}
		}
	}

	/**
	 * Generate and return a {@link HttpClient} configured for general use,
	 * including setting an application-specific user-agent string.
	 */
	public static HttpClient getHttpClient(Context context) {
		final HttpParams params = new BasicHttpParams();

		// Use generous timeouts for slow mobile networks
		HttpConnectionParams
				.setConnectionTimeout(params, 20 * SECOND_IN_MILLIS);
		HttpConnectionParams.setSoTimeout(params, 20 * SECOND_IN_MILLIS);

		HttpConnectionParams.setSocketBufferSize(params, 8192);
		HttpProtocolParams.setUserAgent(params, buildUserAgent(context));

		final DefaultHttpClient client = new DefaultHttpClient(params);

		client.addRequestInterceptor(new HttpRequestInterceptor() {
			public void process(HttpRequest request, HttpContext context) {
				// Add header to accept gzip content
				if (!request.containsHeader(HEADER_ACCEPT_ENCODING)) {
					request.addHeader(HEADER_ACCEPT_ENCODING, ENCODING_GZIP);
				}
			}
		});

		client.addResponseInterceptor(new HttpResponseInterceptor() {
			public void process(HttpResponse response, HttpContext context) {
				// Inflate any responses compressed with gzip
				final HttpEntity entity = response.getEntity();
				final Header encoding = entity.getContentEncoding();
				if (encoding != null) {
					for (HeaderElement element : encoding.getElements()) {
						if (element.getName().equalsIgnoreCase(ENCODING_GZIP)) {
							response.setEntity(new InflatingEntity(response
									.getEntity()));
							break;
						}
					}
				}
			}
		});

		return client;
	}

	/**
	 * Build and return a user-agent string that can identify this application
	 * to remote servers. Contains the package name and version code.
	 */
	private static String buildUserAgent(Context context) {
		try {
			final PackageManager manager = context.getPackageManager();
			final PackageInfo info = manager.getPackageInfo(context
					.getPackageName(), 0);

			// Some APIs require "(gzip)" in the user-agent string.
			return info.packageName + "/" + info.versionName + " ("
					+ info.versionCode + ") (gzip)";
		} catch (NameNotFoundException e) {
			return null;
		}
	}

	/**
	 * Simple {@link HttpEntityWrapper} that inflates the wrapped
	 * {@link HttpEntity} by passing it through {@link GZIPInputStream}.
	 */
	private static class InflatingEntity extends HttpEntityWrapper {
		public InflatingEntity(HttpEntity wrapped) {
			super(wrapped);
		}

		@Override
		public InputStream getContent() throws IOException {
			return new GZIPInputStream(wrappedEntity.getContent());
		}

		@Override
		public long getContentLength() {
			return -1;
		}
	}
}
