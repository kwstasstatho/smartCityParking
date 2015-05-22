package gr.teilam.smartcity.parking.filter;

import android.annotation.SuppressLint;
import android.location.Location;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.widget.Filter;

public abstract class ParkingMarkerFilter {
private static final String LOG_TAG = "Filter";
    
    private static final String THREAD_NAME = "MarkerFilter";
    private static final int FILTER_TOKEN = 0xD0D0FDDD;
    private static final int FINISH_TOKEN = 0xDEADBFFF;

    private Handler mThreadHandler;
    private Handler mResultHandler;

    private final Object mLock = new Object();

    private Delayer mDelayer;

    /**
     * <p>Creates a new Marker Asynchronous Filter.</p>
     */
    public ParkingMarkerFilter() {
        mResultHandler = new MakerResultsHandler();
    }

    /**
     * <p>Starts an asynchronous filtering operation. Calling this method
     * cancels all previous non-executed filtering requests and posts a new
     * filtering request that will be executed later.</p>
     *
     * @param constraint the constraint used to filter the data
     *
     * @see #filter(CharSequence, android.widget.Filter.FilterListener)
     */
    public final void filter(Location constraint, double radius) {
        filter(constraint, radius, null);
    }

    /**
     * Provide an interface that decides how long to delay the message for a given query.  Useful
     * for heuristics such as posting a delay for the delete key to avoid doing any work while the
     * user holds down the delete key.
     *
     * @param delayer The delayer.
     * @hide
     */
    public void setDelayer(Delayer delayer) {
        synchronized (mLock) {
            mDelayer = delayer;
        }
    }
    
    /**
     * <p>Starts an asynchronous filtering operation. Calling this method
     * cancels all previous non-executed filtering requests and posts a new
     * filtering request that will be executed later.</p>
     *
     * <p>Upon completion, the listener is notified.</p>
     *
     * @param constraint the constraint used to filter the data
     * @param listener a listener notified upon completion of the operation
     *
     * @see #filter(CharSequence)
     * @see #performFiltering(CharSequence)
     * @see #publishResults(CharSequence, android.widget.Filter.FilterResults)
     */
    public final void filter(Location constraint, double radius, FilterListener listener) {
        synchronized (mLock) {
            if (mThreadHandler == null) {
                HandlerThread thread = new HandlerThread(
                        THREAD_NAME, android.os.Process.THREAD_PRIORITY_BACKGROUND);
                thread.start();
                mThreadHandler = new RequestHandler(thread.getLooper());
            }

            final long delay = (mDelayer == null) ? 0 : mDelayer.getPostingDelay(constraint);
            
            Message message = mThreadHandler.obtainMessage(FILTER_TOKEN);
    
            RequestArguments args = new RequestArguments();
            // make sure we use an immutable copy of the constraint, so that
            // it doesn't change while the filter operation is in progress
            args.location = constraint != null ? constraint : null;
            args.mdRadius = radius;
            args.listener = listener;
            message.obj = args;
    
            mThreadHandler.removeMessages(FILTER_TOKEN);
            mThreadHandler.removeMessages(FINISH_TOKEN);
            mThreadHandler.sendMessageDelayed(message, delay);
        }
    }

    /**
     * <p>Invoked in a worker thread to filter the data according to the
     * constraint. Subclasses must implement this method to perform the
     * filtering operation. Results computed by the filtering operation
     * must be returned as a {@link android.widget.Filter.FilterResults} that
     * will then be published in the UI thread through
     * {@link #publishResults(CharSequence,
     * android.widget.Filter.FilterResults)}.</p>
     *
     * <p><strong>Contract:</strong> When the constraint is null, the original
     * data must be restored.</p>
     *
     * @param constraint the constraint used to filter the data
     * @return the results of the filtering operation
     *
     * @see #filter(CharSequence, android.widget.Filter.FilterListener)
     * @see #publishResults(CharSequence, android.widget.Filter.FilterResults)
     * @see android.widget.Filter.FilterResults
     */
    protected abstract FilterResults performFiltering(Location constraint, double radius);

    /**
     * <p>Invoked in the UI thread to publish the filtering results in the
     * user interface. Subclasses must implement this method to display the
     * results computed in {@link #performFiltering}.</p>
     *
     * @param constraint the constraint used to filter the data
     * @param results the results of the filtering operation
     *
     * @see #filter(CharSequence, android.widget.Filter.FilterListener)
     * @see #performFiltering(CharSequence)
     * @see android.widget.Filter.FilterResults
     */
    protected abstract void publishResults(Location constraint, FilterResults results);

    /**
     * <p>Converts a value from the filtered set into a CharSequence. Subclasses
     * should override this method to convert their results. The default
     * implementation returns an empty String for null values or the default
     * String representation of the value.</p>
     *
     * @param resultValue the value to convert to a CharSequence
     * @return a CharSequence representing the value
     */
    public CharSequence convertResultToString(Object resultValue) {
        return resultValue == null ? "" : resultValue.toString();
    }

    /**
     * <p>Holds the results of a filtering operation. The results are the values
     * computed by the filtering operation and the number of these values.</p>
     */
    protected static class FilterResults {
        public FilterResults() {
            // nothing to see here
        }

        /**
         * <p>Contains all the values computed by the filtering operation.</p>
         */
        public Object values;

        /**
         * <p>Contains the number of values computed by the filtering
         * operation.</p>
         */
        public int count;
    }

    /**
     * <p>Listener used to receive a notification upon completion of a filtering
     * operation.</p>
     */
    public static interface FilterListener {
        /**
         * <p>Notifies the end of a filtering operation.</p>
         *
         * @param count the number of values computed by the filter
         */
        public void onFilterComplete(Location location);
    }

    /**
     * <p>Worker thread handler. When a new filtering request is posted from
     * {@link android.widget.Filter#filter(CharSequence, android.widget.Filter.FilterListener)},
     * it is sent to this handler.</p>
     */
    private class RequestHandler extends Handler {
        public RequestHandler(Looper looper) {
            super(looper);
        }
        
        /**
         * <p>Handles filtering requests by calling
         * {@link Filter#performFiltering} and then sending a message
         * with the results to the results handler.</p>
         *
         * @param msg the filtering request
         */
        public void handleMessage(Message msg) {
            int what = msg.what;
            Message message;
            switch (what) {
                case FILTER_TOKEN:
                    RequestArguments args = (RequestArguments) msg.obj;
                    try {
                        args.results = performFiltering(args.location, args.mdRadius);
                    } catch (Exception e) {
                        args.results = new FilterResults();
                        Log.w(LOG_TAG, "An exception occured during performFiltering()!", e);
                    } finally {
                        message = mResultHandler.obtainMessage(what);
                        message.obj = args;
                        message.sendToTarget();
                    }

                    synchronized (mLock) {
                        if (mThreadHandler != null) {
                            Message finishMessage = mThreadHandler.obtainMessage(FINISH_TOKEN);
                            mThreadHandler.sendMessageDelayed(finishMessage, 3000);
                        }
                    }
                    break;
                case FINISH_TOKEN:
                    synchronized (mLock) {
                        if (mThreadHandler != null) {
                            mThreadHandler.getLooper().quit();
                            mThreadHandler = null;
                        }
                    }
                    break;
            }
        }
    }

    /**
     * <p>Handles the results of a filtering operation. The results are
     * handled in the UI thread.</p>
     */
    @SuppressLint("HandlerLeak")
	private class MakerResultsHandler extends Handler {
        /**
         * <p>Messages received from the request handler are processed in the
         * UI thread. The processing involves calling
         * {@link Filter#publishResults(CharSequence,
         * android.widget.Filter.FilterResults)}
         * to post the results back in the UI and then notifying the listener,
         * if any.</p> 
         *
         * @param msg the filtering results
         */
        @Override
        public void handleMessage(Message msg) {
            RequestArguments args = (RequestArguments) msg.obj;

            publishResults(args.location, args.results);
            if (args.listener != null) {
                //int count = args.results != null ? args.results.count : -1;
                args.listener.onFilterComplete(args.location);
            }
        }
    }

    /**
     * <p>Holds the arguments of a filtering request as well as the results
     * of the request.</p>
     */
    private static class RequestArguments {
        /**
         * <p>The constraint used to filter the data.</p>
         */
        Location location;
        double mdRadius;
        /**
         * <p>The listener to notify upon completion. Can be null.</p>
         */
        FilterListener listener;

        /**
         * <p>The results of the filtering operation.</p>
         */
        FilterResults results;
    }

    /**
     * @hide
     */
    public interface Delayer {

        /**
         * @param constraint The constraint passed to {@link Filter#filter(CharSequence)}
         * @return The delay that should be used for
         *         {@link Handler#sendMessageDelayed(android.os.Message, long)}
         */
        long getPostingDelay(Location constraint);
    }
}