package com.neovest.fx.ssl.server.resource;

import com.neovest.fx.services.FSSFixService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.management.MBeanServer;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.StringTokenizer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


@RestController
@CrossOrigin
@RequestMapping("/api/v1/")
@Api(value="neovest", description="Operations retrieving fx symbol results")
public class FXDataServiceController {

	private static final Logger logger = LoggerFactory.getLogger(FXDataServiceController.class);
	private static final SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss");
	private static final String eventTerm = "{ \"event\": \"terminated\" }";

	private FSSFixService FSSFS = null;
	private MBeanServer mbs = null;
	private ObjectName mbsname = null;
	ExecutorService service = null;

	public FXDataServiceController() {
		try {
			mbs = ManagementFactory.getPlatformMBeanServer();
			//        org.quickfixj:type=Connector,role=Initiator,id=1
			mbsname = new ObjectName("org.quickfixj:type=Connector,role=Initiator,id=1");
			FSSFS = new FSSFixService();
			ObjectInstance fsstest = mbs.getObjectInstance(mbsname);
			System.out.println("********* Object name from JMX is:"+ fsstest.getObjectName());
			//	setup connection recycling timer
			// ScheduledTasks scheduler = new ScheduledTasks(FSSFS);

			service = Executors.newSingleThreadExecutor();
			service.execute(() -> {
				try {
					FSSFS.logon();
				} catch (Exception e) {
					e.printStackTrace();
				}
			});
		} catch (Exception qe) {
			System.out.println("*********** Exception registering bean:");
		}

	}


	@RequestMapping(value = "fx/server/", produces = "text/event-stream", method = RequestMethod.GET)
	@ApiOperation(value = "reset the server connectinos and emitters", hidden = false)
	@CrossOrigin
	public void resetEmmitterCacheandSession()
			throws Exception {

        try {
        	if(FSSFS.isconnected())
            	FSSFS.resetSession();
        	else
				FSSFS.logon();
            this.FSSFS.removeAllEmitters();
			logger.info("Session reset All emitters removed at {}", dateFormat.format(new Date()));
        } catch (Exception e){
            logger.error("Exception in connect recycle" + e);
        }
        logger.info("Server reset run at {}", dateFormat.format(new Date()));
    }

	@RequestMapping(value = "fx/symbols/{emitterkey}", produces = "text/event-stream", method = RequestMethod.GET)
	@ApiOperation(value = "retrieve all supported fx symbols as json stream as Securities List", hidden = false)
	@CrossOrigin
	public SseEmitter getSecurityList(@PathVariable String emitterkey)
			throws Exception {
		logger.info("submitting request for security list");
		final SseEmitter sse = new SseEmitter();
			FSSFS.sendSecurityListRequest(sse, emitterkey);
		logger.info("submitted request returning emitter");

		return sse;
	}

	@RequestMapping(value = "fx/symbol/{emitterkey}/{stringpair}/{dob}", produces = "text/event-stream", method = RequestMethod.GET)
	@ApiOperation(value = "retrieve json stream of fx symbol pair for all providers in Passthrough mode  ", hidden = false)
	@CrossOrigin
	public SseEmitter getMDR(@PathVariable String emitterkey,
										  @PathVariable String stringpair,
										  @PathVariable int dob)
			throws Exception {

			final SseEmitter sse = new SseEmitter(0L);
		synchronized (FSSFS) {
			FSSFS.subscribeToMarketDataInPassThroughMode(fixSymbolPair(stringpair), sse, emitterkey, dob, null);
		}
		logger.info("submitted request returning emitter");

		return sse;
	}

	@RequestMapping(value = "fx/symbols/{emitterkey}/{stringpair}/{dob}/{providers}/{timeout}/", produces = "text/event-stream", method = RequestMethod.GET)
	@ApiOperation(value = "retrieve json stream of fx symbol pair for defined providers in Passthrough mode ", hidden = false)
	@CrossOrigin
	public SseEmitter getComplexMDRPassthrough(
			@PathVariable String emitterkey,
			@PathVariable String stringpair,
			@PathVariable int dob,
			@PathVariable String providers,
			@PathVariable long timeout)
			throws Exception {

		if(timeout >0 ) timeout = timeout * 1000;
		final SseEmitter sse = new SseEmitter(timeout);
		ArrayList provList = new ArrayList();
		StringTokenizer st = new StringTokenizer(providers,",");
		while (st.hasMoreTokens()) {
			provList.add(st.nextToken());
		}

		synchronized (FSSFS) {
			FSSFS.subscribeToMarketDataInPassThroughMode(fixSymbolPair(stringpair), sse, emitterkey, dob, provList);
		}
		logger.info("submitted request returning emitter");

		return sse;
	}

	@RequestMapping(value = "fx/symbols/{emitterkey}/{stringpair}/{dob}/{providers}/{fullamount}/{timeout}/", produces = "text/event-stream",  method = RequestMethod.GET)
	@ApiOperation(value = "retrieve separate full amount symbol stream with bid/offer for each provider", hidden = false)
	@CrossOrigin
	public SseEmitter getComplexMDRFullAmount(
			@PathVariable String emitterkey,
			@PathVariable String stringpair,
			@PathVariable int dob,
			@PathVariable String providers,
			@PathVariable String fullamount,
			@PathVariable long timeout)
			throws Exception {

		if(timeout >0 ) timeout = timeout * 1000;
		final SseEmitter sse = new SseEmitter(timeout);
		ArrayList provList = new ArrayList();
		StringTokenizer st = new StringTokenizer(providers,",");
		while (st.hasMoreTokens()) {
			provList.add(st.nextToken());
		}
		ArrayList<Integer> amountList = new ArrayList();
		StringTokenizer num = new StringTokenizer(fullamount,",");
		String qtytok = "";
		int test = 0;
		while (num.hasMoreTokens()) {
			qtytok = num.nextToken();
			try {
				test = Integer.parseInt(qtytok);
				amountList.add(Integer.parseInt(qtytok));
			} catch (NumberFormatException nfe) {
				amountList.add(Integer.parseInt(translateAmount(qtytok)));
			}
		}

//		synchronized (FSSFS) {
			FSSFS.subscribeToMarketDataComposite(fixSymbolPair(stringpair), sse, emitterkey, dob, provList, amountList);
//		}
		logger.info("submitted request returning emitter");

		return sse;
	}

	@RequestMapping(value = "fx/quote/{emitterkey}/{stringpair}/{tenor}/{qty}/{providers}/{timeout}/", produces = "text/event-stream",  method = RequestMethod.GET)
	@ApiOperation(value = "retrieve streaming quote for fx symbol as json response", hidden = true)
	@CrossOrigin
	public SseEmitter getQuoteStream(
			@PathVariable String emitterkey,
			@PathVariable String stringpair,
			@PathVariable String tenor,
			@PathVariable Integer qty,
			@PathVariable String providers,
			@PathVariable long timeout)
			throws Exception {

		if(timeout > 0 ) timeout = timeout * 1000;
		final SseEmitter sse = new SseEmitter(timeout);
		ArrayList provList = new ArrayList();
		StringTokenizer st = new StringTokenizer(providers,",");
		while (st.hasMoreTokens()) {
			provList.add(st.nextToken());
		}
		synchronized (FSSFS) {
			FSSFS.subscribeToQuoteStream(fixSymbolPair(stringpair),emitterkey,qty,tenor, provList,0);
		}
		logger.info("submitted request returning emitter");

		return sse;
	}

	@RequestMapping(value = "fx/providers/{emitterkey}", produces = "text/event-stream", method = RequestMethod.GET)
	@ApiOperation(value = "retrieve allowed list of brokers as json stream", hidden = false)
	@CrossOrigin
	public SseEmitter getBrokerList(@PathVariable String emitterkey)throws Exception {

		SseEmitter sse = new SseEmitter();//"STR.UT.SIM.NEOV.9999"
		String[] providers = new String[]{"JPMC","BAML","GS","CITI","BNP","BTMU","MS","UBS","HSBC","CS","SCB","COBA"};
		String fixmsg = FSSFS.getBrokerListRequest(sse, emitterkey, false);
//		int i = 0;
//		for (String provider : providers) {
//			rbe.send("{ \"broker\":\""+ provider + "\" }");
//			i++;
//		}
//		rbe.send(eventTerm);

		return sse;
	}


	private String fixSymbolPair(String originPair){
		String fixed = originPair;
		if(originPair.length() != 7)
			fixed =  originPair.substring(0,3) + "/" + originPair.substring(3,6);
		return fixed;
	}

	private String translateAmount(String qty) {

		int multiplier = 1000000;
		int loc = -1;
        loc = qty.indexOf('m');

		if ( loc < 0){
			loc = qty.indexOf('k');
			multiplier = 1000;
		}
		if ( loc < 0){
			loc = qty.indexOf('b');
			multiplier = 1000000000;
		}
		int notamount = Integer.parseInt(qty.substring(0,loc));
		return  String.valueOf(notamount * multiplier);
	}


}
