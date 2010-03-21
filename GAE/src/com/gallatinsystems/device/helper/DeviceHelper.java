package com.gallatinsystems.device.helper;

import java.util.logging.Logger;

import com.gallatinsystems.device.dao.DeviceDAO;
import com.gallatinsystems.device.domain.Device;

public class DeviceHelper {
	@SuppressWarnings("unused")
	private static final Logger log = Logger
	.getLogger(DeviceHelper.class.getName());
	
	public Device createDevice(Device device){
		DeviceDAO deviceDAO = new DeviceDAO();
		deviceDAO.save(device);
		return (Device)device;
	}


}
