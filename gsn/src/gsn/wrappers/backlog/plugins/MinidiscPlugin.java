package gsn.wrappers.backlog.plugins;

import java.io.Serializable;

import org.apache.log4j.Logger;

import gsn.beans.DataField;


public class MinidiscPlugin extends AbstractPlugin {

	private static DataField[] dataField = {
		  new DataField("TIMESTAMP", "BIGINT"),
		  new DataField("GENERATION_TIME", "BIGINT"),
		  new DataField("DEVICE_ID", "INTEGER"),
		  
		  new DataField("UTC_RAW_DATA", "VARCHAR(32)"),
      new DataField("LAT_RAW_DATA", "VARCHAR(32)"),
      new DataField("LAT_N_RAW_DATA", "VARCHAR(32)"),
      new DataField("LONG_RAW_DATA", "VARCHAR(32)"),
      new DataField("LONG_N_RAW_DATA", "VARCHAR(32)"),
      new DataField("QUAL_RAW_DATA", "VARCHAR(32)"),
      new DataField("SAT_RAW_DATA", "VARCHAR(32)"),
      new DataField("HDOP_RAW_DATA", "VARCHAR(32)"),
      new DataField("GEO_HEIGHT_RAW_DATA", "VARCHAR(32)"),
      new DataField("GEO_HEIGHT_U_RAW_DATA", "VARCHAR(32)"),
      new DataField("GEO_SEP_RAW_DATA", "VARCHAR(32)"),
      new DataField("GEO_SEP_U_RAW_DATA", "VARCHAR(32)"),
		  
		  new DataField("MINIDISC_RAW_DATA", "VARCHAR(256)")};

	private final transient Logger logger = Logger.getLogger( MinidiscPlugin.class );

	@Override
	public short getMessageType() {
		return gsn.wrappers.backlog.BackLogMessage.MINIDISC_MESSAGE_TYPE;
	}

	@Override
	public String getPluginName() {
        return "MinidiscPlugin";
	}

	@Override
	public DataField[] getOutputFormat() {
    return dataField;
	}

	@Override
	public boolean messageReceived(int deviceId, long timestamp, Serializable[] data) {
		logger.debug("message received from CoreStation with DeviceId: " + deviceId);
		
		if (data.length != 15) {
			logger.warn("The message with timestamp >" + timestamp + "< seems unparsable.(length: " + data.length + ")");
			ackMessage(timestamp, super.priority);
			return true;
		}

		try {
		  
		  if( dataProcessed(System.currentTimeMillis(), new Serializable[] {timestamp, timestamp, deviceId, data[0], data[1], data[2], data[3], data[4], data[5], data[6], data[7], data[8], data[9], data[10], data[11], data[14]}) )
        ackMessage(timestamp, super.priority);
      else
        logger.warn("The Minidisc message with timestamp >" + timestamp + "< could not be stored in the database.");
		  
		} catch (Exception e) {
			logger.warn("Minidisc data could not be parsed: " + e.getMessage(), e);
			ackMessage(timestamp, super.priority);
			return true;
		}
		return true;
	}
}