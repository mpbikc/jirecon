/*
 * Jirecon, the Jitsi recorder container.
 * 
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.jitsi.jirecon.task.recorder;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.Map.*;

import net.java.sip.communicator.service.protocol.OperationFailedException;

import org.jitsi.impl.neomedia.recording.*;
import org.jitsi.jirecon.task.JireconTaskSharingInfo;
import org.jitsi.service.libjitsi.LibJitsi;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.format.*;
import org.jitsi.service.neomedia.recording.*;
import org.jitsi.util.Logger;

public class JireconRecorderImpl
    implements JireconRecorder
{
    private Map<MediaType, MediaStream> streams =
        new HashMap<MediaType, MediaStream>();

    private MediaService mediaService;

    private Map<MediaType, RTPTranslator> rtpTranslators =
        new HashMap<MediaType, RTPTranslator>();

    private Map<MediaType, Recorder> recorders =
        new HashMap<MediaType, Recorder>();

    private JireconTaskSharingInfo sharingInfo;

    private boolean isReceiving = false;

    private boolean isRecording = false;

    private static final Logger logger = Logger
        .getLogger(JireconRecorderImpl.class);

    private final String SAVING_DIR;

    public JireconRecorderImpl(String SAVING_DIR,
        JireconTaskSharingInfo sharingInfo,
        Map<MediaType, SrtpControl> srtpControls)
    {
        // Have to make sure that Libjitsi has been started.
        this.mediaService = LibJitsi.getMediaService();
        this.SAVING_DIR = SAVING_DIR;
        this.sharingInfo = sharingInfo;
        createMediaStreams(srtpControls);
    }

    @Override
    public void startRecording(Map<MediaFormat, Byte> formatAndDynamicPTs,
        Map<MediaType, StreamConnector> connectors,
        Map<MediaType, MediaStreamTarget> targets)
        throws OperationFailedException,
        IOException,
        MediaException
    {
        prepareMediaStreams(formatAndDynamicPTs, connectors, targets);
        startReceivingStreams();
        prepareRecorders();
        startRecordingStreams();
    }

    @Override
    public void stopRecording()
    {
        stopRecordingStreams();
        stopReceivingStreams();
        stopTranslators();
    }

    private void prepareMediaStreams(
        Map<MediaFormat, Byte> formatAndDynamicPTs,
        Map<MediaType, StreamConnector> connectors,
        Map<MediaType, MediaStreamTarget> targets)
        throws OperationFailedException
    {
        logger.info("prepareMediaStreams");

        for (Entry<MediaType, MediaStream> e : streams.entrySet())
        {
            final MediaType mediaType = e.getKey();
            final MediaStream stream = e.getValue();

            stream.setConnector(connectors.get(mediaType));
            stream.setTarget(targets.get(mediaType));
            for (Entry<MediaFormat, Byte> f : formatAndDynamicPTs.entrySet())
            {
                if (mediaType == f.getKey().getMediaType())
                {
                    stream.addDynamicRTPPayloadType(f.getValue(), f.getKey());
                    if (null == stream.getFormat())
                    {
                        stream.setFormat(f.getKey());
                    }
                }
            }

            stream.setRTPTranslator(getTranslator(mediaType));
        }
    }

    private void prepareRecorders() throws OperationFailedException
    {
        logger.info("prepareRecorders");

        for (Entry<MediaType, RTPTranslator> e : rtpTranslators.entrySet())
        {
            Recorder recorder = new RecorderRtpImpl(e.getValue());
            recorders.put(e.getKey(), recorder);
        }
    }

    private void startReceivingStreams() throws OperationFailedException
    {
        logger.info("startReceiving");

        int startCount = 0;
        for (Entry<MediaType, MediaStream> e : streams.entrySet())
        {
            MediaStream stream = e.getValue();
            stream.getSrtpControl().start(e.getKey());
            stream.start();
            if (stream.isStarted())
            {
                startCount += 1;
            }
        }

        if (streams.size() != startCount)
        {
            throw new OperationFailedException(
                "Could not start receiving streams",
                OperationFailedException.GENERAL_ERROR);
        }
        isReceiving = true;
    }

    private void startRecordingStreams()
        throws IOException,
        MediaException,
        OperationFailedException
    {
        logger.info("startRecording");
        if (!isReceiving || isRecording)
        {
            throw new OperationFailedException(
                "Could not start recording streams, recorders are not ready.",
                OperationFailedException.GENERAL_ERROR);
        }

        RecorderEventHandler eventHandler =
            new JireconRecorderEventHandler(SAVING_DIR + "/metadata.json");
        for (Entry<MediaType, Recorder> e : recorders.entrySet())
        {
            e.getValue().setEventHandler(eventHandler);
            e.getValue().start(e.getKey().toString(), SAVING_DIR);
        }
        isRecording = true;
    }

    private void stopRecordingStreams()
    {
        logger.info("stopRecording");
        if (!isRecording)
            return;

        for (Entry<MediaType, Recorder> e : recorders.entrySet())
        {
            e.getValue().stop();
            System.out.println("Stop " + e.getKey() + " Over");
        }
        recorders.clear();
        isRecording = false;
    }

    private void stopReceivingStreams()
    {
        logger.info("stopReceiving");
        if (!isReceiving)
            return;

        for (Map.Entry<MediaType, MediaStream> e : streams.entrySet())
        {
            e.getValue().close();
            e.getValue().stop();
        }
        streams.clear();
        isReceiving = false;
    }

    private void stopTranslators()
    {
        for (Entry<MediaType, RTPTranslator> e : rtpTranslators.entrySet())
        {
            e.getValue().dispose();
        }
        rtpTranslators.clear();
    }

    private void createMediaStreams(Map<MediaType, SrtpControl> srtpControls)
    {
        logger.info("prepareMediaStreams");
        for (MediaType mediaType : MediaType.values())
        {
            if (mediaType != MediaType.AUDIO && mediaType != MediaType.VIDEO)
            {
                continue;
            }
            final MediaStream stream =
                mediaService.createMediaStream(null, mediaType,
                    srtpControls.get(mediaType));
            streams.put(mediaType, stream);

            stream.setName(mediaType.toString());
            stream.setDirection(MediaDirection.RECVONLY);
            sharingInfo.addLocalSsrc(mediaType,
                stream.getLocalSourceID() & 0xFFFFFFFFL);
        }
    }

    private RTPTranslator getTranslator(MediaType mediaType)
    {
        if (rtpTranslators.containsKey(mediaType))
        {
            return rtpTranslators.get(mediaType);
        }

        final RTPTranslator translator = mediaService.createRTPTranslator();
        rtpTranslators.put(mediaType, translator);

        return translator;
    }

    private long getAssociatedSsrc(long ssrc, MediaType mediaType)
    {
        Map<String, Map<MediaType, String>> participants =
            sharingInfo.getParticipantsSsrcs();

        if (null == participants)
            return -1;

        for (Entry<String, Map<MediaType, String>> e : participants.entrySet())
        {
            logger.info(e.getKey() + " audio "
                + e.getValue().get(MediaType.AUDIO));
            logger.info(e.getKey() + " video "
                + e.getValue().get(MediaType.VIDEO));
            for (String s : e.getValue().values())
            {
                if (ssrc == Long.valueOf(s))
                {
                    return Long.valueOf(e.getValue().get(mediaType));
                }
            }
        }

        return -1;
    }

    private class JireconRecorderEventHandler
        implements RecorderEventHandler
    {
        private RecorderEventHandler handler;

        public JireconRecorderEventHandler(String filename)
        {
            int count = 1;
            String filenameAvailable = filename;
            while (true)
            {
                File file = new File(filenameAvailable);
                if (file.exists())
                    filenameAvailable = filename + "-" + count++;
                else
                    break;
            }
            try
            {
                handler = new RecorderEventHandlerJSONImpl(filenameAvailable);
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
        }

        @Override
        public void close()
        {
            System.out.println("close");
        }

        @Override
        public synchronized boolean handleEvent(RecorderEvent event)
        {
            System.out.println(event + " ssrc:" + event.getSsrc());
            RecorderEvent.Type type = event.getType();

            if (RecorderEvent.Type.SPEAKER_CHANGED.equals(type))
            {
                System.out.println("SPEAKER_CHANGED audio ssrc: "
                    + event.getAudioSsrc());
                long audioSsrc = event.getAudioSsrc();
                long videoSsrc = getAssociatedSsrc(audioSsrc, MediaType.VIDEO);
                if (videoSsrc < 0)
                {
                    logger
                        .fatal("Could not find video SSRC associated with audioSsrc="
                            + audioSsrc);

                    // don't write events without proper 'ssrc' values
                    return false;
                }

                // for the moment just use the first SSRC
                event.setSsrc(videoSsrc);
            }
            return handler.handleEvent(event);
        }
    }
}
