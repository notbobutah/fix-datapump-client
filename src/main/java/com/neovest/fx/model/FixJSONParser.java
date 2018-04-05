package com.neovest.fx.model;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.neovest.fx.services.FSSFixService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.*;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;


public class FixJSONParser {

	private final Logger log = LoggerFactory.getLogger(FSSFixService.class);
	private LogFactory logFactory ;

	public  int counter = 1;
	BufferedWriter bwJSON;
	ByteArrayOutputStream bufOut = null;
	String fileLocation = "FSS_FIX44.xml";
	ObjectMapper mapper = new ObjectMapper();
	DataDictionary dd = null;


	private static String buildJSONString(String orig) {
		return "\"" + orig + "\"";
	}

	public FixJSONParser(String fileLocation) {
		try {
			this.fileLocation = fileLocation;
			dd = new DataDictionary(fileLocation);
		} catch (Exception e) {
			log.error("Exception throw in parser construvtor: "+ e);
		}
	}
	public FixJSONParser() {
		try {
			this.fileLocation = fileLocation;
			dd = new DataDictionary(fileLocation);
		} catch (Exception e) {
			log.error("Exception throw in parser construvtor: "+ e);
		}
	}

	public String convertToJSON(Message message) throws FieldNotFound, IOException {
		return convertToJSON(dd,message);
	}
	public String convertToJSON(DataDictionary dataDictionary,
								Message message) throws FieldNotFound, IOException {

		bufOut = new ByteArrayOutputStream();

		// long startTime = System.nanoTime();
		ObjectMapper mapper = new ObjectMapper();
		mapper.configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false);
		ObjectNode rootNode = mapper.createObjectNode();
		ObjectNode headerNode = rootNode.putObject("header");
		convertFieldMapToJSON(dataDictionary, message.getHeader(), headerNode);
		convertFieldMapToJSON(dataDictionary, message, rootNode);
		String outputJSON = mapper.writeValueAsString(rootNode);

		return outputJSON;
	}

	public ObjectNode convertToNode(DataDictionary dataDictionary,
									Message message) throws FieldNotFound, IOException {

		bufOut = new ByteArrayOutputStream();

		// long startTime = System.nanoTime();
		ObjectMapper mapper = new ObjectMapper();
		mapper.configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false);
		ObjectNode rootNode = mapper.createObjectNode();
		ObjectNode headerNode = rootNode.putObject("header");
		convertFieldMapToJSON(dataDictionary, message.getHeader(), headerNode);
		convertFieldMapToJSON(dataDictionary, message, rootNode);
		String outputJSON = mapper.writeValueAsString(rootNode);

		return rootNode;
	}

	public   ObjectNode convertFieldMapToJSON(DataDictionary dataDictionary,
	FieldMap fieldmap, ObjectNode node) throws FieldNotFound {


		Iterator<Field<?>> fieldIterator = fieldmap.iterator();
		while (fieldIterator.hasNext()) {
			Field field = fieldIterator.next();
			String value = fieldmap.getString(field.getTag());
			if (!isGroupCountField(dataDictionary, field)) {
				node.put(String.valueOf(field.getTag()), value);
				/*String fieldName = dataDictionary.getFieldName(field.getTag());
				if (fieldName == null){ fieldName = "UDF"+ field.getTag();}
				node.put(fieldName, value);*/
			}
		}

		Iterator groupsKeys = fieldmap.groupKeyIterator();
		while (groupsKeys.hasNext()) {
			int groupCountTag = ((Integer) groupsKeys.next()).intValue();
			 log.debug(groupCountTag + ": count = " + fieldmap.getInt(groupCountTag));
			Group group = new Group(groupCountTag, 0);
			ArrayNode repeatingGroup = node.putArray(String
					.valueOf(groupCountTag));

			int i = 1;
			while (fieldmap.hasGroup(i, groupCountTag)) {
				fieldmap.getGroup(i, group);
				ObjectNode groupNode = repeatingGroup.addObject();
				convertFieldMapToJSON(dataDictionary, group, groupNode);
				i++;
			}
		}
		return node;
	}


	public String convertGroupToJSON(Message message){
		ObjectNode rootNode = null;
		String outputJSON = null;
		try {
			rootNode = mapper.createObjectNode();
			convertGroupMapToJSON(message, rootNode);
			outputJSON = mapper.writeValueAsString(rootNode);
		} catch (Exception ex){
			log.debug("********** Exception inside group to json = " + ex);
		}
		return outputJSON;
	}

	public   ObjectNode convertGroupMapToJSON( FieldMap fieldmap, ObjectNode node) throws FieldNotFound {


//		node.put("Symbol", fieldmap.getString(55));

		Iterator groupsKeys = fieldmap.groupKeyIterator();
		while (groupsKeys.hasNext()) {
			int groupCountTag = ((Integer) groupsKeys.next()).intValue();
			log.debug("Grouptag:"+groupCountTag + ": count = " + fieldmap.getInt(groupCountTag));
			Group group = new Group(groupCountTag, 0);
			ArrayNode repeatingGroup = node.putArray("Quote");

			int i = 1;
			while (fieldmap.hasGroup(i, groupCountTag)) {
				fieldmap.getGroup(i, group);
				ObjectNode groupNode = repeatingGroup.addObject();
				groupNode = buildGroupResponseMap(group, groupNode);
				convertGroupMapToJSON(group, groupNode);
				i++;
			}
		}
//		log.debug("*********** group JSON:"+ node.toString());
		return node;
	}


	public boolean isGroupCountField(DataDictionary dd, Field field) {
		return dd.getFieldTypeEnum(field.getTag()) == FieldType.NumInGroup;

	}

	public ObjectNode buildGroupResponseMap(Group group, ObjectNode node) {
//		HashMap map = new HashMap();
		ObjectNode on = mapper.createObjectNode();
		String label = "";
		Iterator fnit = group.iterator();

		while(fnit.hasNext()){
			Field fld = (Field)fnit.next();
			String fieldObj = fld.toString();
			String fieldVal = fieldObj.substring(fieldObj.indexOf("=")+1, fieldObj.length());

			switch(fld.getField()) {
				case 55: on.put("symbol", fieldVal); break;
				case 268:
					on.put("count", fieldVal);break;
				case 269:
						switch(fieldVal) {
							case "0": label = "BID";break;
							case "1": label = "OFFER";break;
							case "2": label = "TRADE";break;
							case "3": label = "INDEX";break;
							case "4": label = "OPEN";break;
							case "5": label = "CLOSE";break;
							case "6": label = "SETTLE";break;
							case "7": label = "HIGH";break;
							case "8": label = "LOW";break;
							case "9": label = "VWAP";break;
							case "A": label = "BAL";break;
							case "B": label = "VOLUME";break;
							case "C": label = "IOI";break;
						}
//					on.put("type", label);
						break;
				case 270:
					on.put("price", fieldVal);break;
				case 271:
					on.put("volume", fieldVal);break;
				case 272:
					on.put("date", fieldVal);break;
				case 273:
					on.put("time", fieldVal);break;
				case 278:
					on.put("mdorderid", fieldVal);break;
				case 282:
					on.put("provider", fieldVal);break;
			}
		} // end of while
		node.put(label, on);
		return node;

	}
}
