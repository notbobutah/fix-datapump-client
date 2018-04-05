package com.neovest.fx.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class WebUser {
	public String name;
	public String domainName;
	
	public WebUser() {
		
	}
	
	public WebUser(String name, String domainName)
	{
		this.name = name;
		this.domainName = domainName;
	}
	
	@Override
	public String toString() 
	{
		return "name: " + this.name + ", domain: " + this.domainName;
	}
}
