package in.akuj.jplay;

import android.app.job.JobParameters;
import android.app.job.JobService;

public final class CrashUploadJob extends JobService {
    private JobParameters active;
    @Override public boolean onStartJob(JobParameters params) {
        active=params;
        CrashReports.get(this).uploadNow(() -> {if(active==params){active=null;jobFinished(params,false);}});
        return true;
    }
    @Override public boolean onStopJob(JobParameters params){active=null;return true;}
}
