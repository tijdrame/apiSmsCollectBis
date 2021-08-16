package com.boa.api.request;

import lombok.Data;

@Data
public class SmsRequest {

    private String receivers;
    private String smsMessage;
    private String idMsg = "";
    private String idDossier = "";
    private String exploitant = "";
    private Integer nbRelance = 30;
    private String pays;
    private Integer statut;
    private String canalEnvoi;
    private String typeNotif;
    private String emailReceivers = "";
    private Integer compteurRelance = 0;
    private String titreMessage = "";
    private String emailMessage = "";
}
