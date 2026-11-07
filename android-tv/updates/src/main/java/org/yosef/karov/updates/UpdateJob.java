package org.yosef.karov.updates;

import android.app.job.JobParameters;
import android.app.job.JobService;

public final class UpdateJob extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
        UpdateEngine.get(this).background(() -> jobFinished(params, false));
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        UpdateEngine.get(this).cancelDownload();
        return true;
    }
}
