package uk.co.la1tv.offAirPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Timer;
import java.util.TimerTask;

import com.wowza.wms.amf.AMFDataList;
import com.wowza.wms.application.IApplicationInstance;
import com.wowza.wms.client.IClient;
import com.wowza.wms.logging.WMSLogger;
import com.wowza.wms.logging.WMSLoggerFactory;
import com.wowza.wms.logging.WMSLoggerIDs;
import com.wowza.wms.module.ModuleBase;
import com.wowza.wms.request.RequestFunction;
import com.wowza.wms.stream.IMediaStream;
import com.wowza.wms.stream.IMediaStreamActionNotify;
import com.wowza.wms.stream.publish.Stream;

public class OffAirPlugin extends ModuleBase {
	
	private static final String PROP_NAME_PREFIX = "la1OffAirPlugin-";
	private static final String OUTGOING_STREAMS_SUFFIX = "_out";
	private static final String LOG_PREFIX = "LA1 Offair Plugin: ";
	
	private WMSLogger logger = null;
	private IApplicationInstance appInstance = null;
	private StreamListener streamListener = new StreamListener();
	// contains all generated server side Stream objects
	// key is the stream name
	private HashMap<String, ServerSideStream> streams = new HashMap<>();
	// the item to play when the stream goes down
	private String offAirVideo = null;
	
	// time in seconds to show offair video for before closing stream
	private Integer timeToShowOffAirVideoFor = null;
	// streams listed here will always either be live or showing the off air loop
	// default app instance will be assumed (_definst_) but can be provided like so streamName3/myAppInstance
	private ArrayList<String> streamsToRemainLive = new ArrayList<>();
	
	private Timer cleanupTimer = null;
	
	public void onAppStart(IApplicationInstance appInstance) {
		this.appInstance = appInstance;
		logger = WMSLoggerFactory.getLoggerObj(appInstance);
		logger.info(LOG_PREFIX+" Loading module.", WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
		
		offAirVideo = appInstance.getProperties().getPropertyStr(PROP_NAME_PREFIX+"offAirVideo");
		timeToShowOffAirVideoFor = appInstance.getProperties().getPropertyInt(PROP_NAME_PREFIX+"timeToShowOffAirVideoFor", 300);
		String streamsToRemainLiveStr = appInstance.getProperties().getPropertyStr(PROP_NAME_PREFIX+"streamsToRemainLive", "");
		synchronized(streamsToRemainLive) {
			for (String streamName : streamsToRemainLiveStr.split(",")) {
				streamName = streamName.trim();
				if (streamName.equals("")) {
					continue;
				}
				streamsToRemainLive.add(streamName);
			}
		}
		
		cleanupTimer = new Timer(true);
		cleanupTimer.schedule(new CleanupTimerTask(), 5000, 5000);
		
		createInitialStreams();
	}
	
	public void onAppStop(IApplicationInstance appInstance) {
		logger.info(LOG_PREFIX+" Destroying module.", WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
		cleanupTimer.cancel();
		cleanupTimer.purge();
		synchronized(streams) {
			for(Iterator<Entry<String, ServerSideStream>> it = streams.entrySet().iterator(); it.hasNext(); ) {
				Entry<String, ServerSideStream> entry = it.next();
				ServerSideStream stream = entry.getValue();
				// close the stream and remove it
				stream.close();
				it.remove();
			}
		}
	}
	

	
	public String[] extractStreamNameAndAppInstance(String streamNameAndAppInstance) {
		String[] parts = streamNameAndAppInstance.split("/");
		String streamName = parts[0];
		String appInstanceName = "_definst_";
		if (parts.length >= 2) {
			appInstanceName = parts[1];
		}
		if (isOutgoingStreamName(streamName)) {
			throw(new RuntimeException("Stream name not allowed: "+streamName));
		}
		return new String[]{streamName, appInstanceName};
	}
	
	// create server side streams for all streams that are set to always be live
	public void createInitialStreams() {
		synchronized(streamsToRemainLive) {
			for (String streamNameAndAppInstance : streamsToRemainLive) {
				String[] streamNameAndAppInstanceArr = extractStreamNameAndAppInstance(streamNameAndAppInstance);
				String appInstanceName = streamNameAndAppInstanceArr[1];
				String streamName = streamNameAndAppInstanceArr[0];
				if (appInstance.getName().equals(appInstanceName)) {
					// this stream should remain live on this application instance.
					broadcastStream(streamName);
				}
				
			}
		}
	}
	
	public boolean streamShouldRemainLive(String streamNameParam) {
		synchronized(streamsToRemainLive) {
			for (String streamNameAndAppInstance : streamsToRemainLive) {
				String[] streamNameAndAppInstanceArr = extractStreamNameAndAppInstance(streamNameAndAppInstance);
				String appInstanceName = streamNameAndAppInstanceArr[1];
				String streamName = streamNameAndAppInstanceArr[0];
				if (appInstance.getName().equals(appInstanceName) && streamNameParam.equals(streamName)) {
					// this stream should remain live on this application instance.
					return true;
				}
			}
			return false;
		}
	}
	
	// returns true if streamName maps to an outgoing stream
	public boolean isOutgoingStreamName(String streamName) {
		return streamName.endsWith(OUTGOING_STREAMS_SUFFIX);
	}
	
	public void onStreamCreate(IMediaStream stream) {
		stream.addClientListener(streamListener);
	}
	
	
	public void onStreamDestroy(IMediaStream stream) {
		stream.removeClientListener(streamListener);
	}
	
	public void publish(IClient client, RequestFunction function, AMFDataList params) {
		String streamName = params.getString(PARAM1);
		
		logger.info(LOG_PREFIX+" Stream being published: "+streamName, WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
		
		if (!isOutgoingStreamName(streamName)) {
			this.invokePrevious(client, function, params);
		}
		else {
			logger.info(LOG_PREFIX+" Blocked incoming stream \""+streamName+"\" as it ends with the outgoing streams suffix.", WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
		}
	}
	
	public void play(IClient client, RequestFunction function, AMFDataList params) {
		String streamName = params.getString(PARAM1);
		if (isOutgoingStreamName(streamName)) {
			this.invokePrevious(client, function, params);
		}
		else {
			logger.info(LOG_PREFIX+" Someone tried to watch incoming stream with stream name \""+streamName+"\".", WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
		}
	}
	
	// broadcast the stream name with streamNameToBroadcast on a server side stream with the same stream name
	// if a stream already exists with the same stream name then it will be used
	// otherwise a stream will be created, then used.
	private void broadcastStream(String streamNameToBroadcast) {
		String streamName = streamNameToBroadcast;
		
		synchronized(streams) {
			ServerSideStream serverStream = streams.get(streamName);
			if (serverStream == null) {
				// stream doesn't exist yet
				// create a server side stream with the same stream name as the incoming stream and put it on the output application
				serverStream = new ServerSideStream(streamName+OUTGOING_STREAMS_SUFFIX);
				streams.put(streamName, serverStream);
				logger.info(LOG_PREFIX+" Created server side stream for "+streamName, WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
			}
			serverStream.attemptSwitchToStream(streamName);
		}
	}
	
	private class StreamListener implements IMediaStreamActionNotify {

		@Override
		public void onPause(IMediaStream stream, boolean isPause, double location) {
			
		}

		@Override
		public void onPlay(IMediaStream stream, String streamName, double playStart, double playLen, int playReset) {
			
		}

		@Override
		public void onPublish(IMediaStream mediaStream, String streamNameParam, boolean isRecord, boolean isAppend) {
			String streamName = mediaStream.getName();
			if (isOutgoingStreamName(streamName)) {
				// this is being called as a result of a server side stream being published
				return;
			}
			// put the stream on a server side stream.
			broadcastStream(streamName);
		}

		@Override
		public void onSeek(IMediaStream stream, double location) {
			
		}

		@Override
		public void onStop(IMediaStream stream) {
			
		}

		@Override
		public void onUnPublish(IMediaStream mediaStream, String streamNameParam, boolean isRecord, boolean isAppend) {
			String streamName = mediaStream.getName();
			if (isOutgoingStreamName(streamName)) {
				// this is being called as a result of a server side stream being unpublished
				return;
			}
			
			synchronized(streams) {
				ServerSideStream serverSideStream = streams.get(streamName);
				// switch to off air
				serverSideStream.attemptSwitchToStream(null);
			}
		}
	
	}
	
	private class ServerSideStream {
		
		private final Stream stream;
		private String liveStreamName = null;
		private long timeStreamWentOffline = System.currentTimeMillis();
		private boolean streamClosed = false;

		public ServerSideStream(String serverSideStreamName) {
			stream = Stream.createInstance(appInstance, serverSideStreamName);
			stream.setUnpublishOnEnd(false);
		}
		
		// attempt to put incoming stream with streamName on this server side stream
		// if the streamName is null then the off air video will be started
		public synchronized boolean attemptSwitchToStream(String streamName) {
			if (streamClosed) {
				throw(new RuntimeException("This stream has been closed."));
			}
			
			boolean success = false;
			liveStreamName = null;
			String item = "";
			if (streamName != null) {
				stream.setRepeat(false);
				success = stream.play(streamName, -2, -1, true);
				item = "\""+streamName+"\"";
				if (success) {
					liveStreamName = streamName;
				}
			}
			else {
				stream.setRepeat(true);
				success = stream.play("mp4:"+offAirVideo, 0, -1, true);
				item = "off-air video";
				timeStreamWentOffline = System.currentTimeMillis();
			}
			
			if (success) {
				logger.info(LOG_PREFIX+" Put "+item+" on output stream \""+stream.getName()+"\".", WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
			}
			else {
				logger.info(LOG_PREFIX+" Failed to put "+item+" on output stream \""+stream.getName()+"\".", WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
			}
			if (streamName != null && !success) {
				// switching to the stream failed so now attempt to switch to the off air video
				attemptSwitchToStream(null);
			}
			return success;
		}
		
		// terminate this stream
		public synchronized void close() {
			if (streamClosed) {
				throw(new RuntimeException("This stream has already been closed."));
			}
			logger.info(LOG_PREFIX+" Closing output stream \""+stream.getName()+"\".", WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
			stream.close();
			streamClosed = true;
		}
		
		public synchronized boolean isClosed() {
			return streamClosed;
		}
		
		// get the stream name of the stream that's currently going out on this server side stream
		// this will be null if there is no live stream
		public synchronized String getLiveStreamName() {
			return liveStreamName;
		}
		
		// get the stream name of the server side stream
		public String getStreamName() {
			return stream.getName();
		}
		
		// get the time the stream went from being live to offline
		// returns null if stream not offline
		public synchronized Long getTimeStreamWentOffline() {
			if (liveStreamName != null) {
				return null;
			}
			return timeStreamWentOffline;
		}
		
	}
	
	private class CleanupTimerTask extends TimerTask {

		@Override
		public void run() {
			synchronized(streams) {
				for(Iterator<Entry<String, ServerSideStream>> it = streams.entrySet().iterator(); it.hasNext(); ) {
					Entry<String, ServerSideStream> entry = it.next();
					ServerSideStream stream = entry.getValue();
					String streamName = entry.getKey();
					Long timeWentOffline = stream.getTimeStreamWentOffline();
					if (!streamShouldRemainLive(streamName) && timeWentOffline != null && timeWentOffline < System.currentTimeMillis() - (timeToShowOffAirVideoFor*1000)) {
						// close the stream and remove it
						stream.close();
						it.remove();
					}
				}
			}
		}

	}
}
