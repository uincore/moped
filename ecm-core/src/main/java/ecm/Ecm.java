package ecm;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map.Entry;

import db.DataRecord;
import db.DataTableDao;
import messages.InstallAckMessage;
import messages.InstallAckPacket;
import messages.LinkContextEntry;
import messages.LoadAckMessage;
import messages.LoadMessage;
import messages.Message;
import messages.MessageType;
import messages.PublishMessage;
import messages.PublishPacket;
import messages.RestoreAckMessage;
import messages.RestoreAckPacket;
import messages.UninstallAckMessage;
import messages.UninstallAckPacket;
import network.external.CarDriver;
import network.external.CommunicationManager;
import network.external.IoTManager;
import network.internal.EcuManager;

public class Ecm {
	// interact with ECUs
	private EcuManager ecuManager;
	// interact with trusted server
	private CommunicationManager commuManager;
	// interact with IoT server
	private IoTManager iotManager;
	private CarDriver carDriver;
	
	private DataTableDao dbDao;
	// key: plug-in temporary id
	private HashMap<Byte, DataRecord> tmpDBRecords = new HashMap<Byte, DataRecord>();

	// key: plug-in temporary id, value: plug-in name
	private HashMap<Byte, String> id2name4UninstallCache = new HashMap<Byte, String>();
	
	public Ecm() {
		dbDao = new DataTableDao();
	}

	public void init(EcuManager ecuManager, CommunicationManager commuManager,
			IoTManager iotManager, CarDriver carDriver) {
		this.ecuManager = ecuManager;
		this.commuManager = commuManager;
		this.iotManager = iotManager;
		this.carDriver = carDriver;
		
		ecuManager.setEcm(this);
		commuManager.setEcm(this);
		carDriver.setEcm(this);
		iotManager.setEcm(this);
	}
	
	public void start() {
		new Thread(ecuManager).start();
		new Thread(carDriver).start();
		new Thread(iotManager).start();
		new Thread(commuManager).start();
		
		// TODO: make it dynamic later
		// loadPlugins
		loadPlugins(2);
		loadPlugins(3);
	}
	
	public void loadPlugins(int ecuId) {
		// Prepare APPs
		HashMap<String, DataRecord> installedApps = getInstalledApps(ecuId);
		Iterator<Entry<String, DataRecord>> iterator = installedApps.entrySet()
				.iterator();
		while (iterator.hasNext()) {
			Entry<String, DataRecord> entry = iterator.next();
			DataRecord record = entry.getValue();

			int reference = record.getRemoteEcuId();
			String executablePluginName = record.getExecutablePluginName();
			int callbackPortID = record.getCallbackPortID();
			HashMap<String, Integer> portInitialContext = record
					.getPortInitialContext();
			ArrayList<LinkContextEntry> portLinkingContext = record
					.getPortLinkingContext();
			String location = record.getLocation();
			byte[] pluginBytes;
			try {
				pluginBytes = readBytesFromFile(location);
				LoadMessage loadMessage = new LoadMessage(reference,
						executablePluginName, callbackPortID,
						portInitialContext, portLinkingContext, pluginBytes);

				process(loadMessage);
				Thread.sleep(2000);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

		}
	}
	
	private byte[] readBytesFromFile(String location) throws IOException {
		File file = new File(location);
		InputStream is = new FileInputStream(file);
		// Get the size of the file
		long length = file.length();
		// You cannot create an array using a long type.
		// It needs to be an integer type.
		// Before converting to an integer type, check
		// to ensure that file is not larger than Integer.MAX_VALUE.
		if (length > Integer.MAX_VALUE) {
			is.close();
			throw new IOException("Could not completely read file "
					+ file.getName() + " as it is too long (" + length
					+ " bytes, max supported " + Integer.MAX_VALUE + ")");
		}

		// Create the byte array to hold the data
		byte[] bytes = new byte[(int) length];

		// Read in the bytes
		int offset = 0;
		int numRead = 0;
		while (offset < bytes.length
				&& (numRead = is.read(bytes, offset, bytes.length - offset)) >= 0) {
			offset += numRead;
		}

		// Ensure all the bytes have been read in
		if (offset < bytes.length) {
			is.close();
			throw new IOException("Could not completely read file "
					+ file.getName());
		}

		// Close the input stream and return bytes
		is.close();
		return bytes;
	}

	public void process(Message message) {
		String pluginName;
		byte pluginId;
		
		int messageType = message.getMessageType();
		switch (messageType) {
		case MessageType.INSTALL:
		case MessageType.UNINSTALL:
		case MessageType.RESTORE:
		case MessageType.LOAD:
		case MessageType.PWM:
			ecuManager.sendMessage(message);
			break;
		case MessageType.INSTALL_ACK:
			// forward to trusted server
			InstallAckMessage installAckMessage = (InstallAckMessage) message;
			pluginId = installAckMessage.getPluginId();
			pluginName = installAckMessage.getPluginName();
//			System.out.println("@@@ pluginName:"+pluginName);
			updateDB(pluginId);
			int appId = getAppId(pluginName);
			if(pluginName.contains(".zip")) {
				pluginName = pluginName.replace(".zip", ".suite");
			}
			InstallAckPacket installAckPacket = new InstallAckPacket(getVin(), appId, 
					pluginName);
			commuManager.write(installAckPacket);
			break;
		case MessageType.UNINSTALL_ACK:
			// forward to trusted server
			UninstallAckMessage uninstallAckMessage = (UninstallAckMessage) message;
			pluginName = uninstallAckMessage.getPluginName();

			// Remove the PlugIn file in the local and the item in the DB
			DataRecord record = getRecord(pluginName);
			String location = record.getLocation();
			
			deletePlugInFile(location);
			removeRecord(pluginName);
			
			if(pluginName.contains(".zip")) {
				pluginName = pluginName.replace(".zip", ".suite");
			}
			UninstallAckPacket uninstallAckPacket = new UninstallAckPacket(
					getVin(), pluginName);
			
			commuManager.write(uninstallAckPacket);
			break;
		case MessageType.RESTORE_ACK:
			// forward to trusted server
			RestoreAckMessage restoreAckMessage = (RestoreAckMessage) message;
			pluginName = restoreAckMessage.getPluginName();
			RestoreAckPacket restoreAckPacket = new RestoreAckPacket(getVin(), pluginName);
			commuManager.write(restoreAckPacket);
			break;
		case MessageType.PUBLISH:
			// forward to IoT server
			PublishMessage publishMessage = (PublishMessage) message;
			String key = publishMessage.getKey();
			String value = publishMessage.getValue();
			System.out.println("[Publish in ECM] - key: " + key + ", value: "
					+ value);
			PublishPacket publishPacket = new PublishPacket(key, value);
			iotManager.sendPacket(publishPacket);
			break;
		case MessageType.LOAD_ACK:
			LoadAckMessage loadAckMessage = (LoadAckMessage) message;
			pluginName = loadAckMessage.getPluginName();
			System.out.println("[" + pluginName + " loaded]");
			break;
		case MessageType.PLUGIN_MESSAGE:
			System.out.println("iot subscribe message");
			break;
		default:
			System.out.println("Error: Wrong message type");
		}
	}

	// access to DB
	public void insertTmpDBRecord(Byte pluginId, DataRecord dataRecord) {
		tmpDBRecords.put(pluginId, dataRecord);
	}
	
	public String getPluginNameFromTmpDB(Byte pluginId) {
		DataRecord dataRecord = tmpDBRecords.get(pluginId);
		return dataRecord.getPluginName();
	}
	
	public boolean hasPluginInTmpDB(Byte pluginId) {
		return tmpDBRecords.containsKey(pluginId);
	}

	private void removeRecord(String pluginName) {
		dbDao.removeRecord(pluginName);
	}
	
	private void updateDB(byte pluginId) {
		DataRecord dataRecord = tmpDBRecords.get(pluginId);
		String pluginName = dataRecord.getPluginName();
		dbDao.insertRecord(pluginName, dataRecord);
		tmpDBRecords.remove(pluginId);
	}

	private DataRecord getRecord(String pluginName) {
		return dbDao.getRecord(pluginName);
	}


	// save jar file in the ECM
	protected void generateFile(byte[] data, String path) {
		try {
			OutputStream output = null;
			try {
				output = new BufferedOutputStream(new FileOutputStream(path));
				output.write(data);
			} finally {
				output.close();
			}
		} catch (FileNotFoundException ex) {
			System.out.println("File not found.");
		} catch (IOException ex) {
			System.out.println(ex);
		}
	}

	private boolean deletePlugInFile(String filepath) {
		boolean flag = false;
		File file = new File(filepath);
		if (file.isFile() && file.exists()) {
			file.delete();
			flag = true;
		}
		return flag;
	}

	public CommunicationManager getCommuManager() {
		return commuManager;
	}

	public IoTManager getIotManager() {
		return iotManager;
	}

	public HashMap<String, DataRecord> getInstalledApps(int ecuId) {
		HashMap<String, DataRecord> installedAppRecords = dbDao
				.getInstalledAppRecords(ecuId);
		return installedAppRecords;
	}

	private String getVin() {
		return commuManager.getVin();
	}
	
	private int getAppId(String pluginName) {
		return dbDao.getAppId(pluginName);
	}
	
	public String getPluginNameFromUninstallCache(Byte pluginId) {
		return id2name4UninstallCache.get(pluginId);
	}
	
	public void addPluginIdPluginName2UninstallCache(Byte pluginId, String pluginName) {
		id2name4UninstallCache.put(pluginId, pluginName);
	}
	
	public boolean hasPluginInUninstallCache(Byte pluginId) {
		return id2name4UninstallCache.containsKey(pluginId);
	}
}