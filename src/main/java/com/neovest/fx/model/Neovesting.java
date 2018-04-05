package com.neovest.fx.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.neovest.api.symbol.SymbolFieldRecord;
import com.neovest.api.symbol.SymbolRecordEvent;
import com.neovest.neovestworld.MicroSettingsData;
import com.neovest.neovestworld.api.Header;
import io.swagger.annotations.ApiParam;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


@JsonIgnoreProperties(ignoreUnknown=true)
public class Neovesting
{
	private final long id;
	private final String content;
	private final String username;
	private final String domain;
	private final List<String> aList;
	private final Map<String, String> aMap;
	private final Map<String, Object> dataMap;

	private SymbolRecordEvent symbolRecordEvent = null;
	private ArrayList<SymbolFieldRecord> symbolFieldRecords;
	@Deprecated
	private MicroSettingsData sdata = null;
	private Header header = null;

	public enum MessageType
	{
		SETTINGS,
		MARKET_DATA,
		ORDER_ENTRY,
	} 
	
	public Neovesting()
	{
		// Jackson deserialization
		this(0, "no content");
	}

	public Neovesting(long id, String content)
	{
		this(id, content, "Not implemented");
	}

	public Neovesting(MicroSettingsData sdata)
	{
		this(sdata.getId(), sdata.getDomain(), sdata.getUsername());
	}

	public Neovesting(long id, String content, String username)
	{
		this.id = id;
		this.content = content;
		this.domain = "";
		this.username = username;
		aList = new ArrayList<>();
		aMap = new HashMap<>();
		dataMap = new HashMap<>();
		this.symbolFieldRecords = new ArrayList<SymbolFieldRecord>();
		this.header = new Header();
		this.header.setDomainName(this.domain);
		this.header.setPropertyName(this.content);
		this.header.setUserName(this.username);
		this.header.setUpdateDate(Instant.now());
		this.header.setAuthServer("not implmented");
		//this.header.setSettingType(type.ordinal());
		this.header.setRequestId((int)this.id);
		init();
	}
	
	public Neovesting(long id, MessageType type, String content, String domain, String username)
	{
		this.id = id;
		this.content = content;
		this.domain = domain;
		this.username = username;
		aList = new ArrayList<>();
		aMap = new HashMap<>();
		dataMap = new HashMap<>();
		this.symbolFieldRecords = new ArrayList<SymbolFieldRecord>();
		this.header = new Header();
		this.header.setDomainName(this.domain);
		this.header.setPropertyName(this.content);
		this.header.setUserName(this.username);
		this.header.setSettingType(type.ordinal());
		this.header.setRequestId((int)this.id);
		this.header.setUpdateDate(Instant.now());
		init();
	}

	private void init()
	{
		aList.add("one");
		aList.add("two");
		aList.add("three");

		aMap.put("One", "1");
		aMap.put("Two", "2");
		aMap.put("Three", "3");
		
		dataMap.put("key", "value");
	}

	@JsonProperty
	public long getId()
	{
		return id;
	}

	@JsonIgnore
	@ApiParam(hidden=true)
	public String getContent()
	{
		return content;
	}

	@JsonIgnore
	public String getUsername()
	{
		return username;
	}
	
	@JsonIgnore
	public String getDomain()
	{
		return domain;
	}

	@JsonIgnore
	public List<String> getAList()
	{
		return aList;
	}

	@JsonIgnore
	public Map<String, String> getAMap()
	{
		return aMap;
	}

	@JsonIgnore
	public void setAMap(Map<String, String> inMap)
	{
		this.aMap.clear();
		this.aMap.putAll(inMap);
	}

	
	public Map<String, Object> getDataMap()
	{
		return dataMap;
	}
	
	public void setDataMap(Map<String, Object> inMap)
	{
		this.dataMap.clear();
		this.dataMap.putAll(inMap);
	}
	
	@JsonIgnore
	public SymbolRecordEvent getSymbolRecordEvent()
	{
		return this.symbolRecordEvent;
	}

	@JsonIgnore
	public void setSymbolRecordEvent(final SymbolRecordEvent symbolRecordEvent)
	{
		this.symbolRecordEvent = symbolRecordEvent;
	}
	
	@JsonIgnore
	public void addSymbolFieldRecord(SymbolFieldRecord symbolFieldRecord) 
	{
		this.symbolFieldRecords.add(symbolFieldRecord);
	}
	
	@JsonIgnore
	public ArrayList<SymbolFieldRecord> getSymbolFieldRecords() 
	{
		return this.symbolFieldRecords;
	}

	
	@JsonIgnore
	public Header getHeader() 
	{
		return this.header;
	}
	
	@Override
	public String toString()
	{
		String retval = "id=" +id
				+", content=" +content
				+", domain=" +domain
				+", username=" +username
				+", aMap=" +aMap
				+", aList=" +aList
				+", dataMap=" +dataMap;
		return retval;
	}
}
