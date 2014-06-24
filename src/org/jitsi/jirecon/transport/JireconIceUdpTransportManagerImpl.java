/*
 * Jirecon, the Jitsi recorder container.
 * 
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.jitsi.jirecon.transport;

import java.beans.*;
import java.io.IOException;
import java.net.*;
import java.util.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.CandidateType;
import net.java.sip.communicator.service.protocol.OperationFailedException;

import org.ice4j.*;
import org.ice4j.ice.*;
import org.jitsi.jirecon.utils.*;
import org.jitsi.service.configuration.ConfigurationService;
import org.jitsi.service.libjitsi.LibJitsi;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.Logger;

/**
 * An implementation of <tt>JireconTransportManager</tt>.
 * <p>
 * It mainly used for:
 * <p>
 * 1. Establish ICE connectivity.
 * <p>
 * 2. Create <tt>IceUdpTransportPacketExtension</tt>
 * 
 * @author lishunyang
 * @see JireconTransportManager
 * 
 */
public class JireconIceUdpTransportManagerImpl
    implements JireconTransportManager
{
    /**
     * Instance of <tt>Agent</tt>.
     */
    private Agent iceAgent;

    /**
     * Map between <tt>MediaType</tt> and <tt>StreamConnector</tt>. It is used
     * for caching <tt>StreamConnector</tt>.
     */
    private Map<MediaType, StreamConnector> streamConnectors =
        new HashMap<MediaType, StreamConnector>();

    /**
     * Map between <tt>MediaType</tt> and <tt>MediaStreamTarget</tt>. It is used
     * for caching <tt>MediaStreamTarget</tt>.
     */
    private Map<MediaType, MediaStreamTarget> mediaStreamTargets =
        new HashMap<MediaType, MediaStreamTarget>();

    /**
     * The <tt>Logger</tt>, used to log messages to standard output.
     */
    private static final Logger logger = Logger
        .getLogger(JireconIceUdpTransportManagerImpl.class);

    /**
     * The minimum stream port item key in configuration file.
     */
    private String MIN_STREAM_PORT_KEY = "MIN_STREAM_PORT";

    /**
     * The maximum stream port item key in configuration file.
     */
    private String MAX_STREAM_PORT_KEY = "MAX_STREAM_PORT";

    /**
     * The minimum stream port.
     */
    private int MIN_STREAM_PORT;

    /**
     * The maximum stream port.
     */
    private int MAX_STREAM_PORT;

    /**
     * The construction method.
     */
    public JireconIceUdpTransportManagerImpl()
    {
        logger.info("init");
        iceAgent = new Agent();
        ConfigurationService configuration = LibJitsi.getConfigurationService();
        MIN_STREAM_PORT = configuration.getInt(MIN_STREAM_PORT_KEY, -1);
        MAX_STREAM_PORT = configuration.getInt(MAX_STREAM_PORT_KEY, -1);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void free()
    {
        iceAgent.free();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IceUdpTransportPacketExtension getTransportPacketExt()
    {
        logger.info("getTransportPacketExt");
        IceUdpTransportPacketExtension transportPE =
            new IceUdpTransportPacketExtension();
        transportPE.setPassword(iceAgent.getLocalPassword());
        transportPE.setUfrag(iceAgent.getLocalUfrag());
        for (CandidatePacketExtension candidatePE : getLocalCandidatePacketExts())
        {
            transportPE.addCandidate(candidatePE);
        }
        return transportPE;
    }

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <strong>Warning:</strong> This method is asynchronous, it will return
     * immediately while it doesn't means the ICE connectivity has been
     * established successfully. On the contrary, sometime it will never
     * finished and it doesn't matter only if at least one selected candidate
     * pair has been gotten.
     * 
     */
    @Override
    public void startConnectivityEstablishment()
    {
        logger.info("startConnectivityEstablishment");
        new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    startConnectivityCheck();
                }
                catch (OperationFailedException e)
                {
                    e.printStackTrace();
                }
            }
        }).start();
        iceAgent.startConnectivityEstablishment();
    }

    /**
     * Check whether the ICE connectivity has been established successfully.
     * This method seems meaningless.
     * 
     * @throws OperationFailedException if some fatal error occurs.
     */
    private void startConnectivityCheck() throws OperationFailedException
    {
        logger.info("waitForCheckFinished");

        final Object iceProcessingStateSyncRoot = new Object();
        PropertyChangeListener stateChangeListener =
            new PropertyChangeListener()
            {
                public void propertyChange(PropertyChangeEvent evt)
                {
                    Object newValue = evt.getNewValue();

                    if (IceProcessingState.COMPLETED.equals(newValue)
                        || IceProcessingState.FAILED.equals(newValue)
                        || IceProcessingState.TERMINATED.equals(newValue))
                    {
                        if (logger.isTraceEnabled())
                            logger.trace("ICE " + newValue);

                        Agent iceAgent = (Agent) evt.getSource();

                        iceAgent.removeStateChangeListener(this);

                        synchronized (iceProcessingStateSyncRoot)
                        {
                            iceProcessingStateSyncRoot.notify();
                        }
                    }
                }
            };

        iceAgent.addStateChangeListener(stateChangeListener);

        // Wait for the connectivity checks to finish if they have been started.
        boolean interrupted = false;

        synchronized (iceProcessingStateSyncRoot)
        {
            while (IceProcessingState.RUNNING.equals(iceAgent.getState()))
            {
                try
                {
                    iceProcessingStateSyncRoot.wait(1000);
                }
                catch (InterruptedException ie)
                {
                    interrupted = true;
                }
            }
        }
        if (interrupted)
            Thread.currentThread().interrupt();

        /*
         * Make sure stateChangeListener is removed from iceAgent in case its
         * #propertyChange(PropertyChangeEvent) has never been executed.
         */
        iceAgent.removeStateChangeListener(stateChangeListener);

        /* check the state of ICE processing and throw exception if failed */
        if (IceProcessingState.FAILED.equals(iceAgent.getState()))
        {
            throw new OperationFailedException(
                "Could not establish connection (ICE failed)",
                OperationFailedException.GENERAL_ERROR);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void harvestLocalCandidates()
        throws BindException,
        IllegalArgumentException,
        IOException
    {
        logger.info("harvestLocalCandidates");
        for (MediaType mediaType : MediaType.values())
        {
            // Make sure that we only handle audio or video type.
            if (MediaType.AUDIO != mediaType && MediaType.VIDEO != mediaType)
            {
                continue;
            }

            final IceMediaStream stream = getIceMediaStream(mediaType);
            iceAgent.createComponent(stream, Transport.UDP, MIN_STREAM_PORT,
                MIN_STREAM_PORT, MAX_STREAM_PORT);
            iceAgent.createComponent(stream, Transport.UDP, MIN_STREAM_PORT,
                MIN_STREAM_PORT, MAX_STREAM_PORT);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void harvestRemoteCandidates(
        Map<MediaType, IceUdpTransportPacketExtension> transportPEs)
    {
        logger.info("harvestRemoteCandidates");
        for (java.util.Map.Entry<MediaType, IceUdpTransportPacketExtension> e : transportPEs
            .entrySet())
        {
            final MediaType mediaType = e.getKey();
            final IceUdpTransportPacketExtension transportPE = e.getValue();
            final IceMediaStream stream = getIceMediaStream(mediaType);

            final String ufrag =
                JinglePacketParser.getTransportUfrag(transportPE);
            if (null != ufrag)
                stream.setRemoteUfrag(ufrag);

            final String password =
                JinglePacketParser.getTransportPassword(transportPE);
            if (null != password)
                stream.setRemotePassword(password);

            List<CandidatePacketExtension> candidates =
                JinglePacketParser.getCandidatePacketExt(transportPE);
            // Sort the remote candidates (host < reflexive < relayed) in order
            // to create first the host, then the reflexive, the relayed
            // candidates and thus be able to set the relative-candidate
            // matching the rel-addr/rel-port attribute.
            Collections.sort(candidates);
            for (CandidatePacketExtension c : candidates)
            {
                if (c.getGeneration() != iceAgent.getGeneration())
                    continue;
                final Component component =
                    stream.getComponent(c.getComponent());

                String relAddr;
                int relPort;
                TransportAddress relatedAddress = null;

                if (((relAddr = c.getRelAddr()) != null)
                    && ((relPort = c.getRelPort()) != -1))
                {
                    relatedAddress =
                        new TransportAddress(relAddr, relPort,
                            Transport.parse(c.getProtocol()));
                }

                RemoteCandidate relatedCandidate =
                    component.findRemoteCandidate(relatedAddress);

                RemoteCandidate remoteCandidate =
                    new RemoteCandidate(new TransportAddress(c.getIP(),
                        c.getPort(), Transport.parse(c.getProtocol())),
                        component, org.ice4j.ice.CandidateType.parse(c
                            .getType().toString()), c.getFoundation(),
                        c.getPriority(), relatedCandidate);

                if (!canReach(component, remoteCandidate))
                    continue;
                component.addRemoteCandidate(remoteCandidate);
            }
        }
    }

    /**
     * Get <tt>IceMediaStream</tt> of specified <tt>MediaType</tt>.
     * 
     * @param mediaType
     * @return <tt>IceMediaStream</tt>
     */
    private IceMediaStream getIceMediaStream(MediaType mediaType)
    {
        if (null == iceAgent.getStream(mediaType.toString()))
        {
            iceAgent.createMediaStream(mediaType.toString());
        }
        return iceAgent.getStream(mediaType.toString());
    }

    /**
     * Create list of <tt>CandidatePacketExtension</tt>.
     * 
     * @return List of <tt>CandidatePacketExtension</tt>
     */
    private List<CandidatePacketExtension> getLocalCandidatePacketExts()
    {
        List<CandidatePacketExtension> candidatePEs =
            new ArrayList<CandidatePacketExtension>();

        int id = 1;
        for (LocalCandidate candidate : getLocalCandidates())
        {
            CandidatePacketExtension candidatePE =
                new CandidatePacketExtension();
            candidatePE.setComponent(candidate.getParentComponent()
                .getComponentID());
            candidatePE.setFoundation(candidate.getFoundation());
            candidatePE.setGeneration(iceAgent.getGeneration());
            candidatePE.setID(String.valueOf(id++));
            candidatePE.setNetwork(1);
            candidatePE.setIP(candidate.getTransportAddress().getHostAddress());
            candidatePE.setPort(candidate.getTransportAddress().getPort());
            candidatePE.setPriority(candidate.getPriority());
            candidatePE.setProtocol(candidate.getTransport().toString());
            candidatePE.setType(CandidateType.valueOf(candidate.getType()
                .toString()));
            candidatePEs.add(candidatePE);
        }

        return candidatePEs;
    }

    /**
     * Get local candidates
     * 
     * @return List of <tt>LocalCandidate</tt>
     */
    private List<LocalCandidate> getLocalCandidates()
    {
        List<LocalCandidate> candidates = new ArrayList<LocalCandidate>();

        for (MediaType mediaType : MediaType.values())
        {
            // Make sure that we only handle audio or video type.
            if (MediaType.AUDIO != mediaType && MediaType.VIDEO != mediaType)
            {
                continue;
            }

            IceMediaStream stream = getIceMediaStream(mediaType);
            for (Component com : stream.getComponents())
            {
                candidates.addAll(com.getLocalCandidates());
            }
        }

        return candidates;
    }

    // private RemoteCandidate getRelatedCandidate(
    // CandidatePacketExtension candidate, Component component)
    // {
    // if ((candidate.getRelAddr() != null) && (candidate.getRelPort() != -1))
    // {
    // final String relAddr = candidate.getRelAddr();
    // final int relPort = candidate.getRelPort();
    // final TransportAddress relatedAddress =
    // new TransportAddress(relAddr, relPort,
    // Transport.parse(candidate.getProtocol()));
    // return component.findRemoteCandidate(relatedAddress);
    // }
    // return null;
    // }

    /**
     * {@inheritDoc}
     */
    @Override
    public MediaStreamTarget getStreamTarget(MediaType mediaType)
    {
        logger.info("getStreamTarget");
        if (mediaStreamTargets.containsKey(mediaType))
            return mediaStreamTargets.get(mediaType);

        if (mediaType != MediaType.AUDIO && mediaType != MediaType.VIDEO)
            return null;

        IceMediaStream stream = getIceMediaStream(mediaType);
        MediaStreamTarget streamTarget = null;
        if (stream != null)
        {
            List<InetSocketAddress> streamTargetAddresses =
                new ArrayList<InetSocketAddress>();

            for (Component component : stream.getComponents())
            {
                if (component != null)
                {
                    CandidatePair selectedPair = component.getSelectedPair();

                    if (selectedPair != null)
                    {
                        InetSocketAddress streamTargetAddress =
                            selectedPair.getRemoteCandidate()
                                .getTransportAddress();

                        if (streamTargetAddress != null)
                        {
                            streamTargetAddresses.add(streamTargetAddress);
                        }
                    }
                }
            }
            if (streamTargetAddresses.size() >= 2)
            {
                streamTarget =
                    new MediaStreamTarget(
                        streamTargetAddresses.get(0) /* RTP */,
                        streamTargetAddresses.get(1) /* RTCP */);
                mediaStreamTargets.put(mediaType, streamTarget);
            }
        }
        return streamTarget;
    }

    // TODO: Add a maximum wait time, if time out, just throw a
    // OperationFailedException.
    /**
     * {@inheritDoc}
     * <p>
     * <strong>Warning:</strong> This method will wait for the selected
     * candidate pair which should be generated during establish ICE
     * connectivity. However, sometimes selected pair can't be generated
     * forever, in this case, this method will hang.
     */
    @Override
    public StreamConnector getStreamConnector(MediaType mediaType)
        throws OperationFailedException
    {
        logger.info("getStreamConnector");
        if (streamConnectors.containsKey(mediaType))
            return streamConnectors.get(mediaType);
        if (mediaType != MediaType.AUDIO && mediaType != MediaType.VIDEO)
            return null;

        StreamConnector streamConnector = null;
        IceMediaStream stream = getIceMediaStream(mediaType);
        if (null == stream)
        {
            throw new OperationFailedException(
                "Could not get stream connector, ICE media stream was not prepared.",
                OperationFailedException.GENERAL_ERROR);
        }

        List<DatagramSocket> datagramSockets = new ArrayList<DatagramSocket>();
        while (true)
        {
            for (Component component : stream.getComponents())
            {
                if (component != null)
                {
                    CandidatePair selectedPair = component.getSelectedPair();

                    if (selectedPair != null)
                    {
                        datagramSockets.add(selectedPair.getLocalCandidate()
                            .getDatagramSocket());
                    }
                }
            }
            if (datagramSockets.size() >= 2)
            {
                streamConnector =
                    new DefaultStreamConnector(
                        datagramSockets.get(0) /* RTP */,
                        datagramSockets.get(1) /* RTCP */);
                streamConnectors.put(mediaType, streamConnector);
                break;
            }

            // Sleep for 1 second and try again.
            try
            {
                Thread.sleep(1000);
            }
            catch (InterruptedException e)
            {
                e.printStackTrace();
            }
        }

        return streamConnector;
    }

    /**
     * Test whether the remote candidate can reach any local candidate in
     * <tt>Component</tt>.
     * 
     * @param component
     * @param remoteCandidate
     * @return
     */
    private boolean canReach(Component component,
        RemoteCandidate remoteCandidate)
    {
        for (LocalCandidate localCandidate : component.getLocalCandidates())
        {
            if (localCandidate.canReach(remoteCandidate))
                return true;
        }
        return false;
    }
}
