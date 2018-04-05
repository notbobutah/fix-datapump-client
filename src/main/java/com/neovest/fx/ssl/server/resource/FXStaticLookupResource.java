package com.neovest.fx.ssl.server.resource;

import com.codahale.metrics.annotation.Timed;
import com.neovest.fx.model.Neovesting;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.codehaus.jackson.map.ObjectMapper;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;

@RestController
@CrossOrigin
@RequestMapping("/api/v1/")
@Api(value="neovest", description="Operations retrieving FX currency static data")
public class FXStaticLookupResource {

    private static final Logger logger = LoggerFactory.getLogger(FXStaticLookupResource.class);
    private ArrayList displayBasesList = new ArrayList();

    private final Object lookupLock = new Object();
    private boolean resultsReturned = false;
    private static String delim = "|";


    @Autowired
    public FXStaticLookupResource() {
    }


    @RequestMapping(value = "fx/tenors/", method = RequestMethod.GET)
    @ApiOperation(value = "retrieve fx tenor data", hidden = false)
    @Timed
    public Neovesting getTenorsForFX()
            throws InterruptedException {

        Neovesting rtnval = getFXTenors();
        return rtnval;
    }
    @RequestMapping(value = "fx/providers/", method = RequestMethod.GET)
    @ApiOperation(value = "retrieve fx provider data", hidden = false)
    @Timed
    public Neovesting getProvidersForFX()
            throws InterruptedException {

        Neovesting rtnval = getFXProviders();
        return rtnval;
    }

    private Neovesting getFXTenors(){
        HashMap<String,Object> map = null;

        Neovesting retval = new Neovesting(123, Neovesting.MessageType.MARKET_DATA, "Base_LOOKUP", "rmackay", "neovestinc");
        retval.getAList().clear();
        retval.getAMap().clear();
        retval.getDataMap().clear();

        try {
            InputStream is = getClass().getResourceAsStream("/fx_tenors.json");
            JSONTokener tokener = new JSONTokener(is);
            JSONObject jsonObject = new JSONObject(tokener);

            map = new ObjectMapper().readValue(jsonObject.toString(), HashMap.class);

        } catch (Exception e) {
            e.printStackTrace();
        }

        retval.getDataMap().put("fx", map);

        return retval;
    }
    private Neovesting getFXProviders(){
        HashMap<String,Object> map = null;

        Neovesting retval = new Neovesting(456, Neovesting.MessageType.MARKET_DATA, "Base_LOOKUP", "rmackay", "neovestinc");
        retval.getAList().clear();
        retval.getAMap().clear();
        retval.getDataMap().clear();

        try {

            InputStream is = getClass().getResourceAsStream("/fx_providers.json");
            JSONTokener tokener = new JSONTokener(is);
            JSONObject jsonObject = new JSONObject(tokener);
            JSONArray jarray = jsonObject.getJSONArray("Providers");

            map = new ObjectMapper().readValue(jsonObject.toString(), HashMap.class);

        } catch (Exception e) {
            e.printStackTrace();
        }

        retval.getDataMap().put("fx", map);

        return retval;
    }

    private Neovesting emptySearchErr() {
        Neovesting err = new Neovesting();
        err.getDataMap().clear();
        err.getDataMap().put("status", 404);
        err.getDataMap().put("message", "the searchtext was empty");
        err.getDataMap().put("developerMessage", "Empty search strings are invalid in this service");
        return err;
    }
}
