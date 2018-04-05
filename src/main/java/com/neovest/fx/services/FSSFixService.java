package com.neovest.fx.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neovest.fx.model.FixJSONParser;
import com.neovest.fx.model.TwoWayMap;
import org.quickfixj.jmx.JmxExporter;
import org.quickfixj.messages.TradingSessionListRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import quickfix.*;
import quickfix.Message;
import quickfix.MessageCracker;
import quickfix.MessageFactory;
import quickfix.field.*;
import quickfix.field.Currency;
import quickfix.fix44.*;
import quickfix.fix44.component.Parties;
import quickfix.fix50sp2.TradingSessionList;
import quickfix.fix50sp2.component.TrdSessLstGrp;

import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.CountDownLatch;

@Service
public class FSSFixService extends MessageCracker implements Application, Runnable{

    private final Logger log = LoggerFactory.getLogger(FSSFixService.class);
    private LogFactory logFactory ;

    private static final TimeZone UTC_TIMEZONE = TimeZone.getTimeZone("UTC");
    public static final CountDownLatch shutdownLatch = new CountDownLatch(1);

    private Initiator initiator;
    private MessageStoreFactory messageStoreFactory ;
    private MessageFactory messageFactory;
    private DataDictionary dd;
    private boolean initiatorStarted;

    static private final TwoWayMap sideMap = new TwoWayMap();
    static private final TwoWayMap typeMap = new TwoWayMap();
    static private final TwoWayMap tifMap = new TwoWayMap();
    static private final HashMap<SessionID, HashSet<ExecID>> execIDs = new HashMap<>();

    private SessionID trdeps;
    private SessionID streps;

    private SessionID trd;
    private SessionID str;
    private SessionSettings settings = null;
    ObjectMapper mapper = new ObjectMapper();
    private String ccyPair;
    private int nosCounter = 0;
    private boolean symbolFound = false;
    private FSSOption opt;
    private Map<String, SseEmitter> emitterMap = new HashMap<String, SseEmitter>();
    private FixJSONParser fJSONp =  new FixJSONParser();

    public enum FSSOption {
        PASSTHROUGH, FULLAMOUNT
    }
    public void run (){
        try {
            log.info("*********** Starting thread run logon process in FSSFixService....");
            this.logon();
        }catch (Exception e) {
            log.error("Exception caught in run:" + e);
        }
    }
    public FSSFixService() throws Exception {
        loadSettings("");
        messageStoreFactory = new FileStoreFactory(settings);
        logFactory = new SLF4JLogFactory(settings);
        messageFactory = new DefaultMessageFactory();
        dd = new DataDictionary("FSS_FIX44.xml");

        initiator = new SocketInitiator(this, messageStoreFactory, settings, logFactory, messageFactory);
        try {
            final JmxExporter exporter = new JmxExporter();
            ObjectName oname = exporter.register(initiator);
//        org.quickfixj:type=Connector,role=Initiator,id=1
        } catch (org.quickfixj.QFJException qe) {
            System.out.println("*********** Exception registering bean:");
            MBeanInfo mbi = getMBean("SocketInitiator");
        }
    }

    public FSSFixService(SessionSettings settings) throws Exception {
        loadSettings("");
        messageStoreFactory = new FileStoreFactory(settings);
        logFactory = new SLF4JLogFactory(settings);
        messageFactory = new DefaultMessageFactory();
        initiator = new SocketInitiator(this, messageStoreFactory, settings, logFactory, messageFactory);
        final JmxExporter exporter = new JmxExporter();
        MBeanServer mbs = exporter.getMBeanServer();
        ObjectName regobn = exporter.register(initiator);
    }

    public boolean isconnected(){
        return initiatorStarted;
    }

    public SessionID getSession(){
        return streps;
    }
    public void resetSession(){
        try {
            Session.lookupSession(streps).reset();
        } catch(Exception e) {
            log.error("Exception thrown in session reset : " + e);
        }
    }

    public SseEmitter getEmitter(Message  message) throws Exception {
        final String mdReqId = message.getString(MDReqID.FIELD);
        // send resulting json to emitter
        String emitkey = mdReqId.substring(mdReqId.indexOf('_') + 1, mdReqId.length());
        if(emitkey.indexOf('-') > 1)
            emitkey = emitkey.substring(0, emitkey.indexOf('~'));
        if(emitkey.indexOf('~') > 1)
            emitkey = emitkey.substring(0, emitkey.indexOf('~'));
        return emitterMap.get(emitkey);
    }

    public void addEmitter(SseEmitter sse, String key) throws Exception{
        synchronized (this.emitterMap) {
            SseEmitter temp = emitterMap.get(key);
//            temp.onCompletion(() -> { System.out.println("******** onCompletion runnable called when emitter is completed"); });
            if (temp != null && temp.equals(sse)) throw new Exception("Emitter already exists...");
            emitterMap.put(key, sse);
                sse.onCompletion(() -> {
//                    synchronized (this.emitterMap) {
                        log.info("******** SseEmitter completed for :" + key);
                        this.emitterMap.remove(key);
//                    }
                });
        }
            log.info("SseEmitter timeout " + sse.getTimeout() + " for :" + key);
    }
    public SseEmitter getEmitter(String emitkey){
        SseEmitter sse = emitterMap.get(emitkey);
        if(sse == null){
            String shortkey = emitkey.substring(0,emitkey.lastIndexOf('~'));
            sse = emitterMap.get(shortkey);
        }
        return sse;
    }
    public void removeEmitter(String key) {
        synchronized (this.emitterMap) {
            try {
                SseEmitter temp = emitterMap.get(key);
                if (temp == null) throw new Exception("Emitter doesn't exist...");
                emitterMap.remove(key);
            } catch (Exception e) {
                if (e.getMessage().contains("Emitter doesn't exist")) {
                } // do nothing
                else log.error("Error in removeEmitter:" + e);
            }
        }
    }
    public void removeAllEmitters() throws Exception{
        synchronized (this.emitterMap) {
            emitterMap.clear();
        }
    }

    // logout of sessions as part of connection recycling, used in schedledtasks
    public synchronized void logout() throws InterruptedException {
        final Iterator<SessionID> sessionIdtestss = initiator.getSessions().iterator();
        try {
            while (sessionIdtestss.hasNext()) {
                final SessionID sessionId = sessionIdtestss.next();
                System.out.println("inside logout, iterating sessions : " + sessionId);
                Session.lookupSession(sessionId).logout();
            }
        } catch (final Exception e) {
            log.error("*********** inside session logout "+e);
        }
    }

    public synchronized void logon() throws InterruptedException {
        final Iterator<SessionID> sessionIdtestss = initiator.getSessions().iterator();
        while (sessionIdtestss.hasNext()) {
            final SessionID sessionId = sessionIdtestss.next();
            System.out.println("inside logon, iterating sessions : "+ sessionId);
//            Session.lookupSession(sessionId).logon();
        }

        if (!initiatorStarted || !initiator.isLoggedOn()) {
            log.info("*********** inside logon new session ");
            try {
                initiator.start();
                initiatorStarted = true;
            } catch (final Exception e) {
                log.error("Logon failed", e);
                if(e.getMessage().contains("Connector MBean postregistration failed")){
                    final Iterator<SessionID> sessionIds = initiator.getSessions().iterator();
                    while (sessionIds.hasNext()) {
                        final SessionID sessionId = sessionIds.next();
                        Session.lookupSession(sessionId).logon();
                    }
                }
            }
        } else {
            log.info("*********** inside logon in existing session ");
            final Iterator<SessionID> sessionIds = initiator.getSessions().iterator();
            while (sessionIds.hasNext()) {
                final SessionID sessionId = sessionIds.next();
                Session.lookupSession(sessionId).logon();
            }
        }
        shutdownLatch.await();
    }

    public void sendSecurityListRequest(SseEmitter sse, String key) throws Exception {
        final SecurityListRequest slr = new SecurityListRequest();
        String SecId = "SLR_"+ key;
        addEmitter(sse,SecId);
        slr.set(new SecurityReqID(SecId));
        slr.set(new SecurityListRequestType(SecurityListRequestType.ALL_SECURITIES));
        log.info("************  send SLR via Session "+ streps);
        Session.sendToTarget(slr, streps);
    }

    public void sendSingleOrder() throws SessionNotFound {
        final MarketDataRequest mdr = new MarketDataRequest();
        mdr.set(new MDReqID(ccyPair + "_FULLAMOUNT"));
        mdr.set(new SubscriptionRequestType(SubscriptionRequestType.SNAPSHOT_PLUS_UPDATES));
        final MarketDataRequest.NoRelatedSym symbol = new MarketDataRequest.NoRelatedSym();
        symbol.set(new Symbol(ccyPair));
        mdr.addGroup(symbol);
        mdr.set(new MDUpdateType(MDUpdateType.FULL_REFRESH));
        // FSS custom fids for FullAmount
        final int[] quantities = {1000000, 3000000, 5000000, 10000000};
        mdr.setInt(9000, quantities.length);
        // NoRequestedSize (tag 9000)
        for (final int quantity : quantities) {
            final Group group = new Group(9000, 9001);
            group.setInt(9001, quantity);
            // RequestedSize (tag 9001)
            mdr.addGroup(group);
        }
        Session.sendToTarget(mdr, str);
    }

    public void subscribeToQuoteStream(String pair, String uniqueId, int qty, String tenor, ArrayList<String>  partyList, int timeout) throws Exception {

        Message qr = new QuoteRequest();
        qr.setField(new QuoteReqID(pair + "_" + uniqueId));
        qr.setField(new QuoteReqID(pair + "_" + uniqueId));

        // add symbol pair
        QuoteRequest.NoRelatedSym noSymbols = new QuoteRequest.NoRelatedSym();
        noSymbols.set(new Symbol(pair));
        qr.addGroup(noSymbols);

        // add qty
        qr.setField(new OrderQty(qty));
        if (tenor == null || tenor.isEmpty()) tenor = "SP";
        qr.setField(new SettlType(tenor));
        qr.setField(new Currency(pair.substring(0,3)));
        //        qr.setField(new ExpireTime(timeout));

        // add lp party group
        if(partyList != null) {
            addPartyGroup(partyList,qr);
        }

        log.info("************  send quote request via Session str: " + str);
        Session.sendToTarget(qr, str);
    }


    public void unsubscribeFromMarketData(String pair, String uniqueId, SseEmitter sse, String key) throws Exception {
        try {
            log.info("*********** Unsubscribing from marketdata: " + key);
            unsubscribeFromMarketData(new Symbol(pair), key);
            SseEmitter test = getEmitter(key);
            if (test != null) test.complete();
        } catch(Exception e){

        } finally {
            removeEmitter(key);
        }
    }

    public void  unsubscribeFromMarketData(Symbol pair, String uniqueId) throws Exception {
        final MarketDataRequest mdr = new MarketDataRequest();
        mdr.set(new MDReqID(pair.getValue() + "_" + uniqueId));
        mdr.set(new SubscriptionRequestType(SubscriptionRequestType.DISABLE_PREVIOUS_SNAPSHOT_PLUS_UPDATE_REQUEST));
//        final MarketDataRequest.NoRelatedSym symbol = new MarketDataRequest.NoRelatedSym();
//        mdr.addGroup(symbol);
        mdr.set(new MarketDepth(0));
        mdr.setField(pair);
        // Full depth book
        mdr.set(new MDUpdateType(MDUpdateType.FULL_REFRESH));
        log.info("************  send unsubscribe from Session streps: " + streps);
        Session.sendToTarget(mdr, streps);
    }


    public void subscribeToMarketDataInPassThroughMode(String pair, SseEmitter sse, String key, int depth, ArrayList<String>  partyList) throws Exception {
        try {
            addEmitter(sse, key);
            SseEmitter test = getEmitter(key);
        }catch (Exception ex) {
            log.error(ex.getMessage());
        }
        subscribeToMarketDataInPassThroughMode(new Symbol(pair) , key, 5, partyList);
    }

    public void  subscribeToMarketDataInPassThroughMode(Symbol pair, String uniqueId, int depth, ArrayList<String>  partyList) throws Exception {
        final MarketDataRequest mdr = new MarketDataRequest();
        mdr.set(new MDReqID(pair.getValue() + "_" + uniqueId));
        mdr.setBoolean(266,true);
        mdr.set(new MDUpdateType(MDUpdateType.FULL_REFRESH));
//        mdr.set(new MDUpdateType(MDUpdateType.INCREMENTAL_REFRESH));
        mdr.set(new SubscriptionRequestType(SubscriptionRequestType.SNAPSHOT_PLUS_UPDATES));
        final MarketDataRequest.NoRelatedSym symbol = new MarketDataRequest.NoRelatedSym();
        symbol.set(pair);
        mdr.addGroup(symbol);
        mdr.set(new MarketDepth(depth));
        if(partyList != null) {
            addPartyGroup(partyList, mdr);
        }
        // Full depth book
        log.info("************  send MDR PASSTHROUGH via Session streps: " + streps);
        Session.sendToTarget(mdr, streps);
    }

    public void subscribeToMarketDataComposite(String pair, SseEmitter sse, String key, int depth, ArrayList<String>  partyList, ArrayList<Integer> amountList) throws Exception {
        try {
            addEmitter(sse, key);
            SseEmitter test = getEmitter(key);
        }catch (Exception ex) {
            log.error(ex.getMessage());
        }
        ArrayList<String> provSingleList = new ArrayList<String>();
        Iterator iter = partyList.iterator();
        while(iter.hasNext()) {
            String provItem = (String) iter.next();
            provSingleList.clear();
            provSingleList.add(provItem);
            subscribeToMarketDataInFullAmountMode(new Symbol(pair), key, depth, provSingleList,amountList);
        }
    }

    public void subscribeToMarketDataInFullAmountMode(String pair, SseEmitter sse, String key, int depth, ArrayList<String>  partyList, ArrayList<Integer> amountList) throws Exception {
        try {
            addEmitter(sse, key);
            SseEmitter test = getEmitter(key);
        }catch (Exception ex) {
            log.error(ex.getMessage());
        }
            subscribeToMarketDataInFullAmountMode(new Symbol(pair), key, depth, partyList,amountList);
    }

    public void  subscribeToMarketDataInFullAmountMode(Symbol pair, String uniqueId, int depth, ArrayList<String>  partyList, ArrayList<Integer> amountList) throws Exception {
        final MarketDataRequest mdr = new MarketDataRequest();
        if(partyList.size() == 1) {
            String provSingle = partyList.get(0);
            mdr.set(new MDReqID(pair.getValue() + "_" + uniqueId + "~" + provSingle));
        } else {
            mdr.set(new MDReqID(pair.getValue() + "_" + uniqueId));
        }
        mdr.setBoolean(266,true);
        mdr.set(new MDUpdateType(MDUpdateType.FULL_REFRESH));
        mdr.set(new SubscriptionRequestType(SubscriptionRequestType.SNAPSHOT_PLUS_UPDATES));
//        mdr.set(new MDUpdateType(MDUpdateType.INCREMENTAL_REFRESH));
        // FSS custom fids for FullAmount

        final MarketDataRequest.NoRelatedSym symbol = new MarketDataRequest.NoRelatedSym();
        symbol.set(pair);
        mdr.addGroup(symbol);
        mdr.setField(55,pair);
        mdr.set(new MarketDepth(depth));

        if(partyList != null) {
            addPartyGroup(partyList, mdr);
        }
        // quantities
        int[] quantities = new int[amountList.size()];
        Iterator intlist = amountList.iterator();
        int i = 0;
        mdr.setInt(9000, quantities.length);
        while(intlist.hasNext()){
            final Group group = new Group(9000, 9001);
            group.setInt(9001, (int)intlist.next());
            // RequestedSize (tag 9001)
            mdr.addGroup(group);
        }

        log.info("************  send subscribeToMarketDataInFullAmountMode via Session streps: " + streps);
        Session.sendToTarget(mdr, streps);
    }

    public void sendNewOrderSingle(Symbol symbol, Side side, OrderQty orderQty, Price price, String mdEntryID) throws SessionNotFound {
        final NewOrderSingle nos = new NewOrderSingle();
        final Date time = Calendar.getInstance(UTC_TIMEZONE).getTime();
        LocalDateTime ldt = LocalDateTime.ofInstant(time.toInstant(), ZoneId.systemDefault());
        nos.set(new ClOrdID(ccyPair + "_" + nosCounter++));
        nos.set(symbol);
        nos.set(side);
        nos.set(new TransactTime(time));
        nos.set(orderQty);
        nos.set(new OrdType(OrdType.PREVIOUSLY_QUOTED));
        nos.set(price);
        nos.setString(MDEntryID.FIELD, mdEntryID);
        // MDEntryID (tag 278)
        nos.set(new TimeInForce(TimeInForce.FILL_OR_KILL));
        Session.sendToTarget(nos, trd);
    }

    public Message  addPartyGroup(ArrayList<String> partyList, Message message) throws Exception {

        int cnt = 0;
        PartyIDSource prtysrc = new PartyIDSource('D');
        PartyRole prtyrole = new PartyRole(35);
        Parties parties = new Parties();
        Iterator iter = partyList.iterator();
        int fldord[] = new int[]{453,447,448,452};
        while(iter.hasNext()){

            String lp = (String)iter.next();
            Group wrkgroup = new Group(453,1,fldord);
            PartyID prtyid = new PartyID(lp);
            wrkgroup.setField(prtyid);
            wrkgroup.setField(prtysrc);
            wrkgroup.setField(prtyrole);
            message.addGroup(wrkgroup);
            cnt++;
        }
        message.setField(new NoPartyIDs(cnt));

        log.info("************** inside addpartygroup hasgroup  = :" + message.hasGroup(453));
        return message;
    }
    public Message  addSinglePartyGroup(String partyStr, Message message) throws Exception {
        PartyIDSource prtysrc = new PartyIDSource('D');
        PartyRole prtyrole = new PartyRole(35);
        Parties parties = new Parties();
            Group wrkgroup = new Group(453,1);
            PartyID prtyid = new PartyID(partyStr);
            wrkgroup.setField(prtyid);
            wrkgroup.setField(prtysrc);
            wrkgroup.setField(prtyrole);
            message.addGroup(wrkgroup);
        message.setField(new NoPartyIDs(1));

        log.info("************** inside addsinglepartygroup hasgroup  = :" + message.hasGroup(453));
        return message;
    }

    /*      * Application methods      */
    @Override
    public void fromAdmin(Message arg0, SessionID arg1) throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, RejectLogon {
//        log.info("************Message Type = " + arg0.toString());
    }

    @Override
    public void onCreate(SessionID arg0) {

    }

    @Override
    public void onLogon(SessionID sessionId) {
        String compId = sessionId.getSenderCompID();
        if (compId.toLowerCase().startsWith("trd.rfs")) {
            trd = sessionId;
        } else if (compId.toLowerCase().startsWith("trd.ny")) {
            trdeps = sessionId;
        } else if (compId.toLowerCase().startsWith("str.rfs")) {
            str = sessionId;
        } else if (compId.toLowerCase().startsWith("str.ny") ||
                (compId.toLowerCase().startsWith("str.ut"))) {
            streps = sessionId;
        }
    }
    @Override
    public void onLogout(SessionID arg0) {
        log.info("************inside onLogout event handler");
        log.info("************logout of sesssion : " + arg0);
        try {
            this.logon();
        } catch (Exception e) {
            log.info("************ Exception from logon " + e);
        }
    }

    @Override
    public void toAdmin(Message arg0, SessionID arg1) {
    }


    @Override
    public void fromApp(Message message, SessionID sessionID) throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {
        log.info("************ fromApp Message Type = " + message.toString());

        try {
            MsgType mtype = new MsgType();
//            TradingSessionList trdMessage= getMessageClass(message.toString());
            message.getHeader().getField(mtype);
            if(mtype.getValue().equals("BJ")){
                log.error("Caught TradingSessionList response (BJ) MessageType in fromApp"+message.getHeader().getField(mtype));
                onTSLMessage(message, sessionID);
            } else
                crack(message, sessionID);
        } catch (final Exception e) {
            log.error("Problem on session " + sessionID + " with message " + message, e);
        }
    }

    @Override
    public void toApp(Message message, SessionID sessionID) throws DoNotSend {
        log.info("************ toApp Message Type = " + message.toString());
        try {
            MsgType mtype = new MsgType();
            message.getHeader().getField(mtype);
            if(mtype.getValue().equals("BI")){
                log.error("Caught TradingSessionListRequest (BI) MessageType in toApp"+message.getHeader().getField(mtype));
            } else
                crack(message, sessionID);
        } catch (final Exception e) {
            log.error("Problem on session " + sessionID + " with message " + message, e);
        }
    }

    /*      * MessageCracker methods      */

    public void onTSLMessage(Message message, SessionID sessionID) {
        log.info("************Inside provider list response handler");
        try {
            String fixCompId = message.getHeader().getField(new StringField(56)).getValue();
            String compositeKey = message.getField(new StringField(335)).getValue();
            log.info("Inside onMessage for TradingSessionList  -- message compositeKey from 335 is :" + compositeKey);
            TrdSessLstGrp trdGrp = new TrdSessLstGrp();
            TradSesReqID trReqID = new TradSesReqID();
            message.getField(trReqID);
            SseEmitter sendEmitter = getEmitter(trReqID.getValue());
            for(int i=1;i < 13;i++) {
                Group grp = message.getGroup(i, 386);
                String provider = grp.getField(new StringField(336)).getValue();
                log.info("received provider");
                sendEmitter.send(("{ \"broker\":\"" + provider + "\" }"));
            }
            sendEmitter.send(("{ \"event\": \"terminated\"}"));
            unsubscribeFromBrokerList(message, sessionID);
            log.debug("The BrokerList is sent!!!!");
        } catch (final Exception e) {
            log.error("Problem with emitterManager TradingSessionList.", e);
        }
    }

    @Handler
    public void onMessage(TradingSessionList message, SessionID sessionID) throws Exception {
        try {
            String fixCompId = message.getHeader().getField(new StringField(56)).getValue();
            String compositeKey = message.getField(new StringField(335)).getValue();
                log.info("Inside onMessage for TradingSessionList  -- message compositeKey from 335 is :" + compositeKey);
                List<Group> providers = message.getTrdSessLstGrp().getGroups(message.getNoTradingSessions().getTag());
                log.info("providers count:" + providers.size() + " for session:"+ fixCompId);
                MDReqID mdReqID = new MDReqID();
                message.getField(mdReqID);
                SseEmitter sendEmitter = getEmitter(mdReqID.getValue());
                for (Group gr : providers) {
                    String provider = gr.getField(new StringField(336)).getValue();
                    sendEmitter.send(("{ \"broker\":\""+ provider + "\" }"));
                    sendEmitter.send(("{ \"event\": \"terminated\"}"));
                }
                unsubscribeFromBrokerList(message, sessionID);
                sendEmitter.complete();
                log.debug("The BrokerList is completely cached, now we can serve requests!!!!");
        } catch (final Exception e) {
            log.error("Problem with emitterManager TradingSessionList.", e);
        }
    }
    @Handler
    public void onMessage(QuoteRequest message, SessionID sessionID) {
//        log.info("************Quote request");
//        log.info("************Message Type = " + message);
    }
    @Handler
    public void onMessage(QuoteRequestReject message, SessionID sessionID) {
//        log.info("************Quote request reject");
//        log.info("************Message Type = " + message);
    }

    @Handler
    public void onMessage(MarketDataRequest message, SessionID sessionID) {
    }

    @Handler
    public void onMessage(MarketDataRequestReject message, SessionID sessionID) {
        try {
            // MDReqID (tag 262)
            final String mdReqId = message.getString(MDReqID.FIELD);
            log.info("************ sending through SseEmitter for MarketDataSnapshotFullRefresh:" + mdReqId);
            String emitkey = mdReqId.substring(mdReqId.indexOf('_')+1, mdReqId.length());
            SseEmitter sendEmitter = getEmitter(emitkey);
            sendEmitter.send(fJSONp.convertToJSON(message));
            sendEmitter.send(("{ \"reject\": \"received\"}"));
        } catch(Exception e){

        }

    }



//    @Override
    @Handler
    public void onMessage(MarketDataSnapshotFullRefresh message, SessionID sessionID)  {
//        log.info("*************** inside onMessage MarketDataSnapshotFullRefresh :"+ message);

        int i = 0;
        String jsonResp = null;
        HashMap hm = new HashMap();
        SseEmitter sendEmitter = null;
        String emitkey = null;
        final Symbol symbol = new Symbol();
        String mdReqId = null;

        try {

            message.get(symbol);
            final NoMDEntries noMDEntries = new NoMDEntries();
            message.get(noMDEntries);
            /// MsgSeqNum (tag 34)
            final MsgSeqNum msgSeqNum = new MsgSeqNum();
            // MDReqID (tag 262)
            mdReqId = message.getString(MDReqID.FIELD);
            // send resulting json to emitter
            emitkey = mdReqId.substring(mdReqId.indexOf('_')+1,mdReqId.length());

            final MarketDataSnapshotFullRefresh.NoMDEntries mdEntry = new MarketDataSnapshotFullRefresh.NoMDEntries();
            i = noMDEntries.getValue();

            Iterator groupsKeys = message.groupKeyIterator();

            sendEmitter = getEmitter(emitkey);
            log.error(" found emitter "+ sendEmitter +" emitkey "+ emitkey);
            sendEmitter.send(fJSONp.convertGroupToJSON(message));
            }catch ( IllegalStateException ise){
                log.error("Problem with SseEmitter in marketdata");
                // "SseEmitter is already set complete""
                try {
                    if(ise.getMessage().contains("SseEmitter")) {
                        unsubscribeFromMarketData(symbol.getValue(), mdReqId ,sendEmitter,emitkey);
                    }
                } catch(Exception inerre){
                    log.error("Problem removing SseEmitter in marketdata:"+ inerre);
                }
            } catch( Exception e){
                try {
//                    if(e.getMessage().contains("EofException")) {
                        unsubscribeFromMarketData(symbol.getValue(), mdReqId ,sendEmitter,emitkey);
    //                        String pair, String uniqueId, SseEmitter sse, String key
//                    }
                } catch(NullPointerException ne){
                    log.error("Problem removing SseEmitter in marketdata:" + ne);
                }
                catch(Exception inne){
                    log.error("Problem removing SseEmitter in marketdata:"+ inne);
                }
            }
    }

    @Handler
    public void onMessage(SecurityListRequest message, SessionID sessionID) {
    }
    @Handler
    public void onMessage(SecurityList message, SessionID sessionID) {

        try {
            SecurityReqID key = message.getSecurityReqID();
            SseEmitter sse = getEmitter(key.getValue());
            final List<Group> relatedSymbols = message.getGroups(NoRelatedSym.FIELD);
            for (final Group symbolGrp : relatedSymbols) {
                String sym = symbolGrp.getString(Symbol.FIELD);
                String symobj = "{ \"symbol\":\""+ sym + "\" }";
                sse.send(symobj);
            }
            LastFragment lastFrag = new LastFragment();
            message.get(lastFrag);
            if(lastFrag.getValue() == true){
                sse.send("{ \"event\":\"terminated\" }");
//                try {
//                    sse.complete();
//                    this.removeEmitter(key.getValue());
//                } catch(Exception e){}
            }
        } catch (final Exception e) {
            log.error("Problem with SecurityList.", e);
        }
    }
    public String unsubscribeFromBrokerList(Message message, SessionID sessionId) throws Exception {
        String fixMsg = getBrokerListRequest(message, sessionId, true);

        log.debug("******** Unsubscribe broker list using msg : "+ fixMsg);
        return fixMsg;
    }

    private String sideToString(Side side) {
        switch (side.getValue()) {
            case Side.BUY:
                return "BUY";
            case Side.SELL:
                return "SELL";
            default:
                return "";
        }
    }

    //    @Override
    @Handler
    public void onMessage(ExecutionReport message, SessionID sessionID) {
        log.info("*******************Entered onMessage - esxecreport with message:" + message);

        // process the ExecutionReport
        final ExecType execType = new ExecType();
        try {
            message.get(execType);
            if (ExecType.FILL == execType.getValue()) {
                final ClOrdID clOrdID = new ClOrdID();
                message.get(clOrdID);
                final OrderID fssOrdID = new OrderID();
                message.get(fssOrdID);
                final SecondaryOrderID bankOrdID = new SecondaryOrderID();
                message.get(bankOrdID);
                final ExecID fssExecID = new ExecID();
                message.get(fssExecID);
                final SecondaryExecID bankExecID = new SecondaryExecID();
                message.get(bankExecID);
                final Side side = new Side();
                message.get(side);
                log.info(sideToString(side) + " order " + clOrdID.getValue() + " has been filled [bankOrdID=" + bankOrdID.getValue() + ", bankExecID=" + bankExecID.getValue() + ", FSSOrdID=" + fssOrdID.getValue() + ", FSSExecID=" + fssExecID.getValue() + "]");
            } else if (ExecType.REJECTED == execType.getValue()) {
                final ClOrdID clOrdID = new ClOrdID();
                message.get(clOrdID);
                final Side side = new Side();
                message.get(side);
                final Text reason = new Text();
                message.get(reason);
                log.warn(sideToString(side) + " order " + clOrdID.getValue() + " has been rejected (" + reason.getValue() + ").");
            } else if (ExecType.CANCELED == execType.getValue()) {
                final ClOrdID clOrdID = new ClOrdID();
                message.get(clOrdID);
                final Side side = new Side();
                message.get(side);
                final Text reason = new Text();
                message.get(reason);
                log.warn(sideToString(side) + " order " + clOrdID.getValue() + " has been canceled (" + reason.getValue() + ").");
            }
        } catch (final FieldNotFound e) {
            log.error("Could not process executionReport " + message, e);
        }
    }


    private void send(quickfix.Message message, SessionID sessionID) {
        try {
            Session.sendToTarget(message, sessionID);
        } catch (SessionNotFound e) {
            log.error("Exception inside send" + e);
        }
    }

    private void loadSettings(String cfgfile){

        try {
            String osname = System.getProperty("os.name");

            String initfile = null;
            cfgfile = "initiator.cfg";
            if(initfile != null && !initfile.isEmpty()) cfgfile = initfile;

            ClassLoader classLoader = FSSFixService.class.getClassLoader();
            final InputStream inputStream = classLoader.getResourceAsStream(cfgfile);
            settings = new SessionSettings(inputStream);
            inputStream.close();
        } catch (Exception er) {
            log.error("Exception thrown in loadsetting settings:" + er);
        }

    }

    private MBeanInfo getMBean(String beanname) {
            MBeanInfo info = null;
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            Set<ObjectName> objectNames = server.queryNames(null, null);
            for (ObjectName name : objectNames) {
                info = server.getMBeanInfo(name);
                if(info.getClassName().contains(beanname)) {
                    System.out.println("Inside mbean lookup :" + info);
                    break;
                }
            }
        } catch (Exception e){

        }
        return info;
    }

    public String getBrokerListRequest(SseEmitter sse, String emitkey, boolean unsubscribe) throws Exception {
        TradingSessionListRequest tslr = new TradingSessionListRequest();
        addEmitter(sse, emitkey);
        // NOTE: the key passed in as a parameter must have the hostname prepended already
        MDReqID mdReqID = new MDReqID();
        tslr.getHeader().setField(new BeginString("FIX.4.4"));
        tslr.setString(335, emitkey);
//        tslr.setInt(34, 2);
        tslr.setString(49, streps.getSenderCompID());
        tslr.setString(56, streps.getTargetCompID());
        tslr.setUtcTimeStamp(52, new Date(), true);
        tslr.set(new TradSesReqID(emitkey));
        if(unsubscribe) {
            tslr.set(new SubscriptionRequestType(SubscriptionRequestType.DISABLE_PREVIOUS_SNAPSHOT_PLUS_UPDATE_REQUEST));
        } else {
            tslr.set(new SubscriptionRequestType(SubscriptionRequestType.SNAPSHOT_PLUS_UPDATES));
        }
        String fixStr = tslr.toString();
        log.debug("***** Sending TradingSessionListRequest: " + fixStr);
        Session.sendToTarget(tslr, streps);
        return fixStr;
    }
    public String getBrokerListRequest(Message message, SessionID sessionID, boolean unsubscribe) throws Exception {
        TradingSessionListRequest tslr = new TradingSessionListRequest();
        // NOTE: the key passed in as a parameter must have the hostname prepended already
        TradSesReqID trReqID = new TradSesReqID();
        message.getField(trReqID);
        String emitkey = trReqID.getValue();
        tslr.getHeader().setField(new BeginString("FIX.4.4"));
//        tslr.setInt(34, 2);
        tslr.setString(49, streps.getSenderCompID());
        tslr.setString(56, streps.getTargetCompID());
        tslr.setUtcTimeStamp(52, new Date(), true);
        tslr.set(new TradSesReqID(emitkey));
        if(unsubscribe) {
            tslr.set(new SubscriptionRequestType(SubscriptionRequestType.DISABLE_PREVIOUS_SNAPSHOT_PLUS_UPDATE_REQUEST));
        } else {
            tslr.set(new SubscriptionRequestType(SubscriptionRequestType.SNAPSHOT_PLUS_UPDATES));
        }
        String fixStr = tslr.toString();
        log.debug("***** Sending TradingSessionListRequest: " + fixStr);
        Session.sendToTarget(tslr, streps);
        return fixStr;
    }
    // creates MessageSpecific class from FIX message
    // Useful for routing to Quickfix onMessage Handlers.
    public <T extends quickfix.Message> T getMessageClass(String fixmsg) throws Exception {

        Message message = MessageUtils.parse(messageFactory, dd, fixmsg);
        TradingSessionList trdSess = new TradingSessionList();
        Message factorymessage;

        String msgType = message.getHeader().getField(new StringField(35)).getValue();
        if(msgType.equals("BJ") || msgType.equals("BS")) {
            log.debug("Handling a TradingSessionList Response -- " + fixmsg);
            message.getHeader().setField(new BeginString("FIXT.1.19"));
            trdSess = (TradingSessionList)messageFactory.create(message.getHeader().getString(8), message.getHeader().getString(35));
            factorymessage = trdSess;
        }
        else {
            factorymessage = messageFactory.create(message.getHeader().getString(8), message.getHeader().getString(35));
            factorymessage.fromString(fixmsg, dd, false);
        }

        return (T) factorymessage;
    }

}