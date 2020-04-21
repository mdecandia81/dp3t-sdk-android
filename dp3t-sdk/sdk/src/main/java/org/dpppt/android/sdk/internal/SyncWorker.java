/*
 * Created by Ubique Innovation AG
 * https://www.ubique.ch
 * Copyright (c) 2020. All rights reserved.
 */
package org.dpppt.android.sdk.internal;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import com.google.protobuf.ByteString;

import org.dpppt.android.sdk.internal.backend.BackendRepository;
import org.dpppt.android.sdk.internal.backend.ResponseException;
import org.dpppt.android.sdk.internal.backend.models.ApplicationInfo;
import org.dpppt.android.sdk.internal.backend.models.CachedResult;
import org.dpppt.android.sdk.internal.backend.models.ExposedList;
import org.dpppt.android.sdk.internal.backend.models.Exposee;
import org.dpppt.android.sdk.internal.backend.proto.Test;
import org.dpppt.android.sdk.internal.database.Database;
import org.dpppt.android.sdk.internal.logger.Logger;
import org.dpppt.android.sdk.internal.util.DayDate;

import static org.dpppt.android.sdk.internal.util.Base64Util.fromBase64;

public class SyncWorker extends Worker {

	private static final String TAG = "org.dpppt.android.sdk.internal.SyncWorker";

	public static void startSyncWorker(Context context) {
		Constraints constraints = new Constraints.Builder()
				.setRequiredNetworkType(NetworkType.CONNECTED)
				.build();

		PeriodicWorkRequest periodicWorkRequest = new PeriodicWorkRequest.Builder(SyncWorker.class, 15, TimeUnit.MINUTES)
				.setConstraints(constraints)
				.build();

		WorkManager workManager = WorkManager.getInstance(context);
		workManager.enqueueUniquePeriodicWork(TAG, ExistingPeriodicWorkPolicy.KEEP, periodicWorkRequest);
	}

	public static void stopSyncWorker(Context context) {
		WorkManager workManager = WorkManager.getInstance(context);
		workManager.cancelAllWorkByTag(TAG);
	}

	public SyncWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
		super(context, workerParams);
	}

	@NonNull
	@Override
	public Result doWork() {
		Context context = getApplicationContext();

		long scanInterval = AppConfigManager.getInstance(getApplicationContext()).getScanInterval();
		TracingService.scheduleNextClientRestart(context, scanInterval);
		TracingService.scheduleNextServerRestart(context);

		try {
			doSync(context);
			AppConfigManager.getInstance(context).setLastSyncNetworkSuccess(true);
		} catch (IOException | ResponseException e) {
			AppConfigManager.getInstance(context).setLastSyncNetworkSuccess(false);
			return Result.retry();
		}

		return Result.success();
	}

	public static void doSync(Context context) throws IOException, ResponseException {
		AppConfigManager appConfigManager = AppConfigManager.getInstance(context);
		appConfigManager.updateFromDiscoverySynchronous();
		ApplicationInfo appConfig = appConfigManager.getAppConfig();

		Database database = new Database(context);
		database.generateContactsFromHandshakes(context);

		BackendRepository backendRepository =
				new BackendRepository(context, appConfig.getBackendBaseUrl());

		DayDate dateToLoad = new DayDate();
		dateToLoad = dateToLoad.subtractDays(14);

		for (int i = 0; i <= 14; i++) {

			CachedResult<ExposedList> result = backendRepository.getExposees(dateToLoad);
			if (result.isFromCache()) {
				//ignore if result comes from cache, we already added it to database
				//continue;
			}

			Test.ProtoExposedList.Builder builder = Test.ProtoExposedList.getDefaultInstance().toBuilder();
			int counter = 0;
			for (Exposee exposee : result.getData().getExposed()) {
				builder.addExposed(counter, Test.ProtoExposee.getDefaultInstance().toBuilder()
						.setOnset(exposee.getOnset().getStartOfDayTimestamp())
						.setKey(ByteString.copyFrom(fromBase64(exposee.getKey())))
						.build());
				counter++;
			}
			Test.ProtoExposedList exposedList = builder.build();
			File file = new File(context.getCacheDir(), "protoTest.proto");
			file.delete();
			GZIPOutputStream gzipOutputStream = new GZIPOutputStream(new FileOutputStream(file));
			exposedList.writeTo(gzipOutputStream);
			gzipOutputStream.finish();
			gzipOutputStream.flush();
			gzipOutputStream.close();
			Logger.d("Proto", dateToLoad.formatAsString() + " file size is: " + file.length());

			for (Exposee exposee : result.getData().getExposed()) {
				database.addKnownCase(
						context,
						exposee.getKey(),
						exposee.getOnset(),
						dateToLoad
				);
			}

			dateToLoad = dateToLoad.getNextDay();
		}

		database.removeOldKnownCases();

		appConfigManager.setLastSyncDate(System.currentTimeMillis());

		BroadcastHelper.sendUpdateBroadcast(context);
	}

}
