/*
 * Jirecon, the Jitsi recorder container.
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.jitsi.jirecon;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.*;
import java.util.*;

import net.java.sip.communicator.impl.protocol.jabber.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;

import org.jitsi.impl.neomedia.recording.RecorderEventHandlerJSONImpl;
import org.jitsi.jirecon.TaskManagerEvent.*;
import org.jitsi.jirecon.protocol.extension.*;
import org.jitsi.jirecon.utils.*;
import org.jitsi.service.configuration.*;
import org.jitsi.service.libjitsi.*;
import org.jitsi.util.*;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.provider.*;
import org.jivesoftware.smackx.*;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;


/**
 * The manager of <tt>Task</tt>s. Each <tt>Task</tt> represents a
 * recording task for a specific Jitsi Meet conference.
 *
 * @author lishunyang
 */
public class TaskManager
    implements JireconEventListener
{
    /**
     * The <tt>Logger</tt> used by the <tt>TaskManager</tt> class and its
     * instances to print debug information.
     */
    private static final Logger logger = Logger.getLogger(TaskManager.class);

    /**
     * List of <tt>EventListener</tt>.
     */
    private List<JireconEventListener> listeners =
        new ArrayList<JireconEventListener>();

    /**
     * The <tt>XMPPConnection</tt> used by this <tt>TaskManager</tt> (shared
     * between all <tt>JireconTask</tt>s).
     */
    private XMPPConnection connection;

    /**
     * Maps an ID of a Jitsi Meet conference (the JID of the MUC) to the
     * <tt>JireconTask</tt> for the conference.
     */
    private final Map<String, Task> tasks = new HashMap<String, Task>();

    /**
     * The base directory to stage recording files. <tt>JireconImpl</tt> will
     * stage each recording in its own subdirectory of the base directory.
     */
    private String baseStagingDir;

    /**
     * The base output directory to save the output file after staging is completed.
     */
    private String baseOutputDir;
    
    private static String finalOutputDir;

	public static String getFinalOutputDir() {
		return finalOutputDir;
	}

    /**
     * Indicates whether <tt>JireconImpl</tt> has been initialized.
     */
    private boolean isInitialized = false;

    /**
     * Initialize <tt>Jirecon</tt>.
     * <p>
     * Once this method has been executed successfully, <tt>Jirecon</tt> should
     * be ready to start working.
     *
     * Start Libjitsi, load configuration file and create connection with XMPP
     * server.
     *
     * @param configurationPath is the configuration file path.
     * @throws Exception if failed to initialize Jirecon.
     *
     */
    synchronized public void init(String configurationPath)
        throws Exception
    {
        logger.info("Initialize.");

        if (isInitialized)
        {
            logger.warn("Already initialized: ", new Throwable());
            return;
        }

        initializePacketProviders();

        LibJitsi.start();

        System.setProperty(
                ConfigurationService.PNAME_CONFIGURATION_FILE_NAME,
                configurationPath);
        System.setProperty(
                ConfigurationService.PNAME_CONFIGURATION_FILE_IS_READ_ONLY,
                "true");
        final ConfigurationService cfg = LibJitsi.getConfigurationService();

		baseStagingDir = cfg.getString(ConfigurationKey.STAGING_DIR_KEY);
		if (StringUtils.isNullOrEmpty(baseStagingDir)) {
			throw new Exception("Failed to initialize Jirecon: staging "
					+ "directory not set.");
		}

		// Remove the suffix '/'
		if (baseStagingDir.endsWith("/")) {
			baseStagingDir = baseStagingDir.substring(0,
					baseStagingDir.length() - 1);
		}

		baseOutputDir = cfg.getString(ConfigurationKey.SAVING_DIR_KEY);
		if (StringUtils.isNullOrEmpty(baseOutputDir)) {
			throw new Exception("Failed to initialize Jirecon: output "
					+ "directory not set.");
		}

		// Remove the suffix '/'
		if (baseOutputDir.endsWith("/")) {
			baseOutputDir = baseOutputDir.substring(0,
					baseOutputDir.length() - 1);
		}

        final String xmppHost = cfg.getString(ConfigurationKey.XMPP_HOST_KEY);
        final int xmppPort = cfg.getInt(ConfigurationKey.XMPP_PORT_KEY, -1);
        final String xmppUser = cfg.getString(ConfigurationKey.XMPP_USER_KEY);
        final String xmppPass = cfg.getString(ConfigurationKey.XMPP_PASS_KEY);

        try
        {
            connect(xmppHost, xmppPort, xmppUser, xmppPass);
        }
        catch (XMPPException e)
        {
            logger.info("Failed to initialize Jirecon: " + e);
            uninit();
            throw e;
        }

        isInitialized = true;
    }

    /**
     * Uninitialize <tt>Jirecon</tt>, prepare for GC.
     * <p>
     * <strong>Warning:</tt> If there are any residue <tt>JireconTask</tt>s,
     * </tt>Jirecon</tt> will stop them and notify <tt>JireconEventListener</tt>
     * s.
     *
     * Stop Libjitsi and close connection with XMPP server.
     *
     */
    synchronized public void uninit()
    {
        logger.info("Un-initialize");
        if (!isInitialized)
        {
            logger.warn("Not initialized: ", new Throwable());
            return;
        }

        synchronized (tasks)
        {
            for (Task task : tasks.values())
            {
                task.uninit(true);
            }
        }
        closeConnection();
        LibJitsi.stop();
    }

    /**
     * Create a new recording task for a specified Jitsi-meeting.
     * <p>
     * <strong>Warning:</strong> This method is asynchronous, it will return
     * immediately regardless of whether the task has been started successfully.
     * Note that a return value of <tt>false</tt> means that the task has
     * failed to start, while a return value of <tt>true</tt> does not guarantee
     * success.
     *
     * @param mucJid indicates the Jitsi Meet conference to record.
     * @return <tt>true</tt> if the task was initiated asynchronously (and its
     * success is unknown), or <tt>false</tt> if the task failed to be initiated.
     */
    public boolean startJireconTask(String mucJid)
    {
        logger.info("Starting jirecon task: " + mucJid);

        Task task;
        synchronized (tasks)
        {
            if (tasks.containsKey(mucJid))
            {
                logger.info("Not starting duplicate task: " + mucJid);
                return false;
            }
            task = new Task();
            tasks.put(mucJid, task);
        }

        String outputDir =
            baseStagingDir + "/" + mucJid
                + new SimpleDateFormat("-yyMMdd-HHmmss").format(new Date());

		finalOutputDir = outputDir;

        task.addEventListener(this);
        task.init(mucJid, connection, outputDir);

        task.start();
        return true;
    }

    /**
     * Stops a recording task for a specified Jitsi Meet conference.
     *
     * @param mucJid the MUC JID of the Jitsi Meet conference to stop.
     * @param keepData Whether to keep the output files or delete them.
     * @return <tt>true</tt> if the task was stopped successfully and
     * <tt>false</tt> otherwise.
     */
    public boolean stopJireconTask(String mucJid, boolean keepData)
    {
        logger.info("Stopping task: " + mucJid);

        Task task;
        synchronized (tasks)
        {
            task = tasks.remove(mucJid);
        }

        if (task == null)
        {
            logger.info("Failed to stop non-existent task: " + mucJid);
            return false;
        }
        else
        {
            task.stop();
            task.uninit(keepData);
            // moveOutput(baseStagingDir, baseOutputDir);
            createEndpointMappingJson();
        }
        return true;
    }
    
    
    @SuppressWarnings("unchecked")
	public void createEndpointMappingJson() {
		HashMap<String, String> hm = new HashMap<String, String>();
		hm = RecorderEventHandlerJSONImpl.getEndpointMapping();
		String outDir = getFinalOutputDir();
		 

		JSONArray endpoints = new JSONArray();
		for (String string : hm.keySet()) {
			JSONObject obj = new JSONObject();
			obj.put("id", string);
			logger.info("\n " + string);
			logger.info(hm.get(string));
			obj.put("displayName", hm.get(string));
			endpoints.add(obj);

		}

		RecorderEventHandlerJSONImpl.getEndpointMapping().clear();

		try {
			FileWriter file = new FileWriter(outDir + "/endpoints.json");

			logger.info(file);

			file.write(endpoints.toJSONString());
			logger.info("endpoints.json created");
			file.flush();
			file.close();
			endpoints.clear();
		} catch (IOException i) {
			i.printStackTrace();
		}

	}

    
    

    /**
     * Creates {@link #connection} and connects to the XMPP server.
     *
     * @param xmppHost is the host name of XMPP server.
     * @param xmppPort is the port of XMPP server.
     * @param xmppUser the XMPP username to use (should NOT include the domain).
     * Use <tt>null</tt> to login anonymously.
     * @param xmppPass the XMPP password.
     * @throws XMPPException in case of failure to connect and login.
     */
    private void connect(String xmppHost, int xmppPort,
                         String xmppUser, String xmppPass)
        throws XMPPException
    {
        ConnectionConfiguration conf =
            new ConnectionConfiguration(xmppHost, xmppPort);
        connection = new XMPPConnection(conf);
        connection.connect();

        // Register Jingle Features.
        ServiceDiscoveryManager discoManager
            = ServiceDiscoveryManager.getInstanceFor(connection);
        if (discoManager != null)
        {
            discoManager.addFeature(
                ProtocolProviderServiceJabberImpl.URN_XMPP_JINGLE_RTP_VIDEO);
            discoManager.addFeature(
                ProtocolProviderServiceJabberImpl.URN_XMPP_JINGLE_RTP_AUDIO);
            discoManager.addFeature(
                ProtocolProviderServiceJabberImpl.URN_XMPP_JINGLE_ICE_UDP_1);

            // XXX(gp) I'm hard coding the dtls-sctp feature here because it is
            // not yet part of ProtocolProviderServiceJabberImpl. I'm unsure if
            // it should be.
            discoManager.addFeature("urn:xmpp:jingle:transports:dtls-sctp:1");
        }
        else
        {
            logger.warn("Failed to register disco#info features.");
        }

        // Login either anonymously or with a provided username & password.
        if (StringUtils.isNullOrEmpty(xmppUser)
            || StringUtils.isNullOrEmpty(xmppPass))
        {
            logger.info("Logging in as XMPP client anonymously.");
            connection.loginAnonymously();
        }
        else
        {
            logger.info("Logging in as XMPP client using: host=" + xmppHost
                        + "; port=" + xmppPort + "; user=" + xmppUser);
            connection.login(xmppUser, xmppPass);
        }
    }

    /**
     * Closes the XMPP connection.
     */
    private void closeConnection()
    {
        logger.info("Closing the XMPP connection.");
        if (connection != null && connection.isConnected())
            connection.disconnect();
    }

    /**
     * Register our <tt>PacketExtensionProvider</tt>s with Smack's
     * <tt>ProviderManager</tt>.
     */
    private void initializePacketProviders()
    {
        ProviderManager providerManager = ProviderManager.getInstance();

        providerManager.addIQProvider(
                JingleIQ.ELEMENT_NAME,
                JingleIQ.NAMESPACE,
                new JingleIQProvider());
        providerManager.addExtensionProvider(
                MediaExtension.ELEMENT_NAME,
                MediaExtension.NAMESPACE,
                new MediaExtensionProvider());
        providerManager.addExtensionProvider(
                SctpMapExtension.ELEMENT_NAME,
                SctpMapExtension.NAMESPACE,
                new SctpMapExtensionProvider());
    }

    /**
     * Adds a <tt>JireconEventListener</tt>.
     *
     * @param listener the <tt>JireconEventListener</tt> to add.
     */
    public void addEventListener(JireconEventListener listener)
    {
        logger.info("Adding JireconEventListener: " + listener);
        listeners.add(listener);
    }

    /**
     * Removes a <tt>JireconEventListener</tt>.
     *
     * @param listener the <tt>JireconEventListener</tt> to remove.
     */
    public void removeEventListener(JireconEventListener listener)
    {
        logger.info("Removing JireconEventListener: " + listener);
        listeners.remove(listener);
    }

    /**
     * {@inheritDoc}
     *
     * Implements {@link JireconEventListener#handleEvent(TaskManagerEvent)}.
     */
    @Override
    public void handleEvent(TaskManagerEvent evt)
    {
        String mucJid = evt.getMucJid();

        switch (evt.getType())
        {
        case TASK_ABORTED:
            stopJireconTask(mucJid, false);
            logger.info("Recording task of MUC " + mucJid + " failed.");
            fireEvent(evt);
            break;
        case TASK_FINISED:
            stopJireconTask(mucJid, true);
            logger.info("Recording task of MUC: " + mucJid
                + " finished successfully.");
            fireEvent(evt);
            moveOutput(baseStagingDir,baseOutputDir, mucJid);
            break;
        case TASK_STARTED:
            logger.info("Recording task of MUC " + mucJid + " started.");
            fireEvent(evt);
            break;
        default:
            break;
        }
    }

    /**
     * Notifies the registered <tt>JireconEventListener</tt> of a
     * <tt>TaskManagerEvent</tt>.
     *
     * @param evt the event to send.
     */
    private void fireEvent(TaskManagerEvent evt)
    {
        for (JireconEventListener l : listeners)
        {
            l.handleEvent(evt);
        }
    }

    public void moveOutput(String stagingPath, String outputPath, String mucJid) {
	File stagingFolder = new File(stagingPath);
	File outputFolder = new File(outputPath);

	// make sure source exists
	if (!stagingFolder.exists()) {

		logger.error("Directory does not exist.");
		return;
	} else {

		try {

			String files[] = stagingFolder.list();

			for (String file : files) {
				if (file.matches(mucJid + ".*")) {
					if (!outputFolder.exists()) {
						outputFolder.mkdir();
					}

					String command = "mv " + getBaseStagingDir() + "/" + file + " " + getBaseOutputDir();

					Process p = Runtime.getRuntime().exec(command);
					p.waitFor();
				}
			}

		} catch (IOException e) {
			e.printStackTrace();
			return;
		} catch (InterruptedException intException) {
			intException.printStackTrace();
			return;
		}
	}

	logger.info("Done moving media from staging to output");
    }

    public String getBaseStagingDir() {
	return baseStagingDir;
    }

    public String getBaseOutputDir() {
	return baseOutputDir;
    }
}
