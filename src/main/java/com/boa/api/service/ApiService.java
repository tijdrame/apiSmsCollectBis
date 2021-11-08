package com.boa.api.service;

import com.boa.api.config.ApplicationProperties;
import com.boa.api.domain.ParamEndPoint;
import com.boa.api.domain.ParamGeneral;
import com.boa.api.domain.Tracking;
import com.boa.api.response.GenericResponse;
import com.boa.api.service.utils.ICodeDescResponse;
import com.boa.api.service.utils.Utils;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;

//import io.swagger.models.Path;
//import io.swagger.v3.oas.models.Paths;
import lombok.RequiredArgsConstructor;
import org.apache.commons.io.FileUtils;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.FileSystemUtils;

@Service
@Transactional
@RequiredArgsConstructor
public class ApiService {

    private final TrackingService trackingService;
    // private final UserService userService;
    private final Utils utils;
    private final ParamEndPointService endPointService;
    private final ParamGeneralService paramGeneralService;
    private final ApplicationProperties applicationProperties;
    private final Logger log = LoggerFactory.getLogger(ApiService.class);

    @Scheduled(fixedDelayString = "PT5M")
    public void smsProcessing() {
        log.info("== Enter in smsProcessing at ===[{}]", Instant.now());
        Tracking tracking = new Tracking();
        tracking.setDateRequest(Instant.now());
        try {
            Optional<ParamEndPoint> endPoint = endPointService.findByCodeParam("smsCollect");
            if (!endPoint.isPresent()) {
                tracking = createTracking(tracking, ICodeDescResponse.ECHEC_CODE, "smsProcessing",
                        "End point non paramétré", "CRON du " + Instant.now(), "");
                trackingService.save(tracking);
                log.error("End point smsCollect non paramétré");
                return;
            }

            Optional<ParamGeneral> paramGen = paramGeneralService.findByCode("smsCollect");
            if (!endPoint.isPresent()) {
                tracking = createTracking(tracking, ICodeDescResponse.ECHEC_CODE, "smsProcessing",
                        "Repertoire non paramétré", "CRON du " + Instant.now(), "");
                trackingService.save(tracking);
                log.error("Repertoire smsCollect non paramétré");
                return;
            }
            File file = new File(paramGen.get().getVarString1());
            List<String> fileRead = readFilesForFolder(file);
            for (String it : fileRead) {
                Tracking iTracking = new Tracking();
                iTracking.dateRequest(Instant.now());
                GenericResponse genericResp = new GenericResponse();
                List<String> oneLineOfMsg = getLineofMsg(it);
                if (oneLineOfMsg == null || oneLineOfMsg.isEmpty()) {
                    tracking = createTracking(tracking, ICodeDescResponse.ECHEC_CODE, "smsProcessing",
                            "Format de fichier incorrect a la ligne = " + it, "CRON du " + Instant.now(), "");
                    trackingService.save(tracking);
                    log.error("Format de fichier incorrect");
                    continue;
                }
                String jsonStr = new JSONObject().put("receivers", oneLineOfMsg.get(2))
                        .put("sms_message", oneLineOfMsg.get(1)).put("id_msg", "").put("id_dossier", "")
                        .put("exploitant", "").put("nbr_relance", 30).put("pays", applicationProperties.getPays())
                        .put("statut", 1).put("canal_envoi", "SMS").put("type_notif", "ALERTE")
                        .put("email_receivers", "").put("compteur_relance", 0)
                        .put("titre_msg", "NOTIFICATION RAPPEL DIRECTEUR AGENCE").put("email_message", "").toString();
                HttpURLConnection conn = utils.doConnexion(endPoint.get().getEndPoints(), jsonStr, "application/json",
                        null, null);
                BufferedReader br = null;
                JSONObject obj = new JSONObject();
                String result = "";
                log.info("resp code envoi [{}]", conn.getResponseCode());
                if (conn != null && conn.getResponseCode() == 200) {
                    br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    String ligne = br.readLine();
                    while (ligne != null) {
                        result += ligne;
                        ligne = br.readLine();
                    }
                    log.info("smsProcessing result ===== [{}]", result);
                    obj = new JSONObject(result);
                    obj = obj.getJSONObject("wfcredit_sla").getJSONObject("response");
                    log.info("ob to str =[{}]", obj.toString());
                    if (obj.toString() != null && !obj.isNull("code_retour") && obj.get("code_retour").equals("0100")) {
                        genericResp.setCode(ICodeDescResponse.SUCCES_CODE);
                        genericResp.setDescription(obj.getString("message_retour"));
                        genericResp.setDateResponse(Instant.now());
                        iTracking = createTracking(iTracking, ICodeDescResponse.SUCCES_CODE,
                                endPoint.get().getEndPoints(), genericResp.toString(), jsonStr,
                                genericResp.getResponseReference());
                    } else {
                        genericResp.setCode(ICodeDescResponse.ECHEC_CODE);
                        genericResp.setDateResponse(Instant.now());
                        genericResp.setDescription(obj.getString("message_retour"));
                        iTracking = createTracking(iTracking, ICodeDescResponse.ECHEC_CODE,
                                endPoint.get().getEndPoints(), genericResp.toString(), jsonStr,
                                genericResp.getResponseReference());
                    }
                } else {
                    br = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
                    String ligne = br.readLine();
                    while (ligne != null) {
                        result += ligne;
                        ligne = br.readLine();
                    }
                    log.info("resp envoi error ===== [{}]", result);
                    obj = new JSONObject(result);
                    obj = new JSONObject(result);
                    genericResp.setCode(ICodeDescResponse.ECHEC_CODE);
                    genericResp.setDateResponse(Instant.now());
                    genericResp.setDescription(ICodeDescResponse.ECHEC_DESCRIPTION);
                    iTracking = createTracking(iTracking, ICodeDescResponse.ECHEC_CODE, endPoint.get().getEndPoints(),
                            genericResp.toString(), jsonStr, genericResp.getResponseReference());
                }
                trackingService.save(iTracking);
            }
            if (!fileRead.isEmpty()) {
                FileSystemUtils.copyRecursively(new File(paramGen.get().getVarString1()),
                        new File(paramGen.get().getVarString2()));
                FileUtils.cleanDirectory(new File(paramGen.get().getVarString1()));
            }
        } catch (Exception e) {
            GenericResponse genericResp = new GenericResponse();
            genericResp.setCode(ICodeDescResponse.ECHEC_CODE);
            genericResp.setDateResponse(Instant.now());
            genericResp.setDescription(ICodeDescResponse.ECHEC_DESCRIPTION + " " + e.getMessage());
            tracking = createTracking(tracking, ICodeDescResponse.ECHEC_CODE, "smsCollect", genericResp.toString(),
                    "CRON", genericResp.getResponseReference());
            trackingService.save(tracking);
        }
    }

    private static List<String> getLineofMsg(String src) {
        List<String> result = new ArrayList<>();
        src = src.substring(2);
        String[] tab = null;
        String[] msg = null;
        if (src != null) {
            tab = src.split(";;");
            if (tab != null && tab.length == 2) {
                msg = tab[1].split(";");
                result.add(tab[0]); // date
                if (msg != null && msg.length == 2) {
                    result.add(msg[0]); // msg
                    result.add(msg[1]); // tel
                } else
                    return null;
            } else
                return null;
        } else
            return null;

        return result;
    }

    public Tracking createTracking(Tracking tracking, String code, String endPoint, String result, String req,
            String reqId) {
        tracking.setRequestId(reqId);
        tracking.setCodeResponse(code);
        tracking.setDateResponse(Instant.now());
        tracking.setEndPoint(endPoint);
        tracking.setLoginActeur("x");
        tracking.setResponseTr(result);
        tracking.setRequestTr(req);
        tracking.setDateResponse(Instant.now());
        return tracking;
    }

    public static List<String> readFilesForFolder(final File folder) throws IOException {
        List<String> list = new ArrayList<>();
        for (final File fileEntry : folder.listFiles()) {
            if (fileEntry.isDirectory()) {
                readFilesForFolder(fileEntry);
            } else {
                BufferedReader br = new BufferedReader(new FileReader(fileEntry));
                String st = "";
                while ((st = br.readLine()) != null) {
                    list.add(st);
                }
                br.close();
            }
        }
        return list;
    }

    public List<String> testFilesWalk() {
        Path path = Paths.get("C:\\Program Files\\Java\\jdk1.8.0_201");
        List<String> result = new ArrayList<>();
        try (Stream<Path> subPaths = Files.walk(path, 1)) {// le 1 est optionnel; s'il manque il lit les sous repertoires aussi
            /*
             * subPaths //.forEach(a -> System.out.println(a));
             * .filter(Files::isRegularFile)//sans les dossiers
             * .forEach(System.out::println); // affiche tous les fichiers
             */
            List<String> subPathList = subPaths.filter(Files::isRegularFile).map(Objects::toString)
                    .collect(Collectors.toList());
            int i = 1;
            for (String it : subPathList) {
                result.add(i + "e Fichier ***************************************");
                if (it.contains("txt")) {
                    File f = new File(it);
                    BufferedReader br = new BufferedReader(new FileReader(f));
                    String st = "";
                    while ((st = br.readLine()) != null) {
                        result.add(st);
                    }
                    br.close();
                }

                i += 1;
                // System.out.println(it);
            }
        } catch (Exception e) {
            // TODO: handle exception
        }
        return result;
    }

    public static void main(String[] args) throws IOException {
        /*
         * final File folder = new File("D:\\src\\smsCollect\\repertoireDepot");
         * List<String> st = readFilesForFolder(folder);
         * System.out.println("result = "+st.size());
         */
        /*
         * String test =
         * ";;17/06/2021;;BOA SENEGAL : Votre compte bancaire enregistre des impayes. Merci de le provisionner ou passer a votre a agence pour regler ce probleme.;221777092077"
         * ; test = test.substring(2); String[] tab = test.split(";;"); String[] msg =
         * tab[1].split(";");
         * 
         * System.out.println("result = " + tab[0] + tab.length);
         * System.out.println("msg = " + msg[0]); System.out.println("tel = " + msg[1]);
         */
        Path path = Paths.get("C:\\Program Files\\Java\\jdk1.8.0_201");
        List<String> result = new ArrayList<>();
        try (Stream<Path> subPaths = Files.walk(path, 1)) {// le 1 est optionnel; s'il manque il lit les sous
                                                           // repertoires aussi
            /*
             * subPaths //.forEach(a -> System.out.println(a));
             * .filter(Files::isRegularFile)//sans les dossiers
             * .forEach(System.out::println); // affiche tous les fichiers
             */
            List<String> subPathList = subPaths.filter(Files::isRegularFile).map(Objects::toString)
                    .collect(Collectors.toList());
            int i = 1;
            for (String it : subPathList) {
                result.add(i + "e Fichier ***************************************");
                if (it.contains("txt")) {
                    File f = new File(it);
                    BufferedReader br = new BufferedReader(new FileReader(f));
                    String st = "";
                    while ((st = br.readLine()) != null) {
                        result.add(st);
                    }
                    br.close();
                }

                i += 1;
                // System.out.println(it);
            }
        } catch (Exception e) {
            // TODO: handle exception
        }
        for (String it : result) {
            System.out.println(it);
        }
    }
}
