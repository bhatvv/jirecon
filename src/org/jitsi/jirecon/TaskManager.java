/*
/*
 * Jirecon, the JItsi REcording COntainer.
 *
 *
 * Copyright @ 2015 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.jirecon;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.*;
import java.util.*;

import net.java.sip.communicator.impl.protocol.jabber.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.java.sip.communicator.util.Logger;

import org.jitsi.impl.neomedia.recording.RecorderEventHandlerJSONImpl;
import org.jitsi.jirecon.TaskManagerEvent.*;
import org.jitsi.jirecon.protocol.extension.*;
import org.jitsi.jirecon.utils.*;
import org.jitsi.jirecon.xmppcomponent.ComponentLauncher;
import org.jitsi.jirecon.xmppcomponent.XMPPComponent;
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
    private static final net.java.sip.communicator.util.Logger logger = Logger.getLogger(TaskManager.class);
    /**
     * List of <tt>EventListener</tt>, if something important happen,
     * they will be notified.
     */
    private List<JireconEventListener> listeners =
        new ArrayList<JireconEventListener>();

    /**
     * An instance of <tt>XMPPConnection</tt>, it is shared with every
     * <tt>JireconTask</tt>
     */
    private XMPPConnection connection;

    /**
     * Active <tt>JireconTask</tt>, map between Jitsi-meeting jid and task.
     */
    private final Map<String, Task> tasks =
        new HashMap<String, Task>();

    /**
     * The base directory to save recording files. <tt>JireconImpl</tt> will add
     * date suffix to it as a final output directory.
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
     * Indicates whether <tt>JireconImpl</tt> has been initialized, it is used
     * to avoid double initialization.
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
        // Remove the suffix '/' in SAVE_DIR
		baseOutputDir = cfg.getString(ConfigurationKey.SAVING_DIR_KEY);
		if (StringUtils.isNullOrEmpty(baseOutputDir)) {
			throw new Exception("Failed to initialize Jirecon: output "
					+ "directory not set.");
		}


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
     * <strong>Warning:</tt> If there is any residue <tt>JireconTask</tt>,
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
     * immediately while it doesn't mean the task has been started successfully.
     * If the task failed, it will notify event listeners.
     * 
     * @param mucJid indicates which Jitsi-meeting you want to record.
     * @return true if the task has been started successfully, otherwise false.
     *         Notice that the task may fail during the execution.
     */
    public boolean startJireconTask(String mucJid)
    {
        //logger.info("Starting jirecon task: " + mucJid);
        
        logger.audit("RTCServer:" +ComponentLauncher.host+", MucID:"
    			+mucJid + ", RoutingID :" + XMPPComponent.getRoutingId() +", Message:"+"Starting jirecon task: " + mucJid);

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
     * Stop a recording task for a specified Jitsi-meeting.
     * 
     * @param mucJid indicates which Jitsi-meeting you want to record.
     * @param keepData Whether keeping the data. Keep the output files if it is
     *            true, otherwise remove them.
     * @return true if the task has been stopped successfully, otherwise false,
     *         such as task is not found.
     */
    public boolean stopJireconTask(String mucJid, boolean keepData)
    {
        //logger.info("Stopping task: " + mucJid);
        
        logger.audit("RTCServer:" +ComponentLauncher.host+", MucID:"
    			+mucJid + ", RoutingID :" + XMPPComponent.getRoutingId() +", Message:"+"Stopping jirecon task: " + mucJid);

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
			//logger.info("\n " + string);
			//logger.info(hm.get(string));
			obj.put("displayName", hm.get(string));
			endpoints.add(obj);

		}

		RecorderEventHandlerJSONImpl.getEndpointMapping().clear();

		try {
			FileWriter file = new FileWriter(outDir + "/endpoints.json");

			//logger.info(file);

			file.write(endpoints.toJSONString());
			//logger.info("endpoints.json created");
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
            //logger.info("Logging in as XMPP client anonymously.");
            
            logger.audit("RTCServer:" +ComponentLauncher.host+", MucID:"
        			+XMPPComponent.getRoomName() + ", RoutingID :" + XMPPComponent.getRoutingId() +", Message:"+"Logging in as XMPP client anonymously.");
            
            
            connection.loginAnonymously();
        }
        else
        {
            /*logger.info("Logging in as XMPP client using: host=" + xmppHost
                        + "; port=" + xmppPort + "; user=" + xmppUser);*/
            
            logger.audit("RTCServer:" +ComponentLauncher.host+", MucID:"
        			+XMPPComponent.getRoomName() + ", RoutingID :" + XMPPComponent.getRoutingId() +", Message:"+"Logging in as XMPP client using: host=" + xmppHost
                        + "; port=" + xmppPort + "; user=" + xmppUser);
            
            connection.login(xmppUser, xmppPass);
            
            
            
        }
    }

    /**
     * Close XMPP connection.
     */
    private void closeConnection()
    {
        /*logger.info("Closing the XMPP connection.");*/
        
        logger.audit("RTCServer:" +ComponentLauncher.host+", MucID:"
    			+XMPPComponent.getRoomName() + ", RoutingID :" + XMPPComponent.getRoutingId() +", Message:"+"Closing the XMPP connection.");
        
        if (connection != null && connection.isConnected())
            connection.disconnect();
    }

    /**
     * Register the <tt>PacketExtensionProvider</tt>s.
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
        //logger.info("Adding JireconEventListener: " + listener);
    	
    	
        logger.audit("RTCServer:" +ComponentLauncher.host+", MucID:"
    			+XMPPComponent.getRoomName() + ", RoutingID :" + XMPPComponent.getRoutingId() +
    			", Message:"+"Adding JireconEventListener: " + listener);
        
        listeners.add(listener);
    }

    /**
     * Remove an event listener.
     * 
     * @param listener is the event listener you want to remove.
     */
    public void removeEventListener(JireconEventListener listener)
    {
    	
    	 
    	
       // logger.info("Removing JireconEventListener: " + listener);
    	
    	logger.audit("RTCServer:" +ComponentLauncher.host+", MucID:"
     			+XMPPComponent.getRoomName() + ", RoutingID :" + XMPPComponent.getRoutingId() +
     			", Message:"+"Removing JireconEventListener: " + listener);
    	
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
            
            
           /* logger.info("Recording task of MUC " + mucJid + " failed.");*/
            
            logger.audit("RTCServer:" +ComponentLauncher.host+", MucID:"
         			+XMPPComponent.getRoomName() + ", RoutingID :" + XMPPComponent.getRoutingId() +
         			", Message:"+"Recording task of MUC " + mucJid + " failed.");
            
            fireEvent(evt);
            break;
        case TASK_FINISED:
            stopJireconTask(mucJid, true);
            
            
           /* logger.info("Recording task of MUC: " + mucJid
                + " finished successfully.");*/
            
            logger.audit("RTCServer:" +ComponentLauncher.host+", MucID:"
         			+XMPPComponent.getRoomName() + ", RoutingID :" + XMPPComponent.getRoutingId() +
         			", Message:"+"Recording task of MUC: " + mucJid
                + " finished successfully.");
            
            fireEvent(evt);
            createEndpointMappingJson();
            moveOutput(baseStagingDir,baseOutputDir, mucJid);
            break;
        case TASK_STARTED:
      /*      logger.info("Recording task of MUC " + mucJid + " started.");*/
            
            logger.audit("RTCServer:" +ComponentLauncher.host+", MucID:"
         			+XMPPComponent.getRoomName() + ", RoutingID :" + XMPPComponent.getRoutingId() +
         			", Message:"+"Recording task of MUC " + mucJid + " started.");
            
            fireEvent(evt);
            break;
        default:
            break;
        }
    }

    /**
     * Notify the listeners.
     * 
     * @param evt is the event that you want to send.
     */
    private void fireEvent(TaskManagerEvent evt)
    {
        for (JireconEventListener l : listeners)
        {
            l.handleEvent(evt);
        }
    }

    public void moveOutput(String stagingPath, String outputPath, String mucJid) 
    {
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
  			//e.printStackTrace();
  			return;
  		} catch (InterruptedException intException) {
  			//intException.printStackTrace();
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
