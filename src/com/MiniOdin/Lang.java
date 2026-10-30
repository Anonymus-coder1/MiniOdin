package com.MiniOdin;

import android.content.Context;

public class Lang {
    static boolean en;
    static final String[][] T = {
        {"connect", "Connecter (OTG)", "Connect (OTG)"},
        {"empty", "(vide)", "(empty)"},
        {"start", "START", "START"},
        {"free", "Flash libre (.img / .bin)", "Free flash (.img / .bin)"},
        {"pick", "Choisir un .img / .bin", "Pick an .img / .bin"},
        {"none", "Aucun fichier", "No file"},
        {"unk", "partition inconnue", "unknown partition"},
        {"warn", "USERDATA", "USERDATA"},
        {"wipe", "USERDATA efface TOUTES les données du téléphone cible. Continuer ?", "USERDATA erases ALL data on the target phone. Continue?"},
        {"cancel", "Annuler", "Cancel"},
        {"yes", "Oui, effacer", "Yes, erase"},
        {"clearlog", "Effacer le journal", "Clear log"},
        {"clearfiles", "Retirer les fichiers", "Remove files"},
        {"opts", "Options", "Options"},
        {"repart", "Repartitionner peut rendre le téléphone inutilisable (brick). Continuer ?", "Repartition can brick the phone. Continue?"},
        {"sure", "Oui, je suis sûr", "Yes, I'm sure"},
        {"w_reboot", "Êtes-vous vraiment sûr ? Le téléphone redémarrera tout seul à la fin, même si un fichier n'a pas été flashé correctement, ce qui peut causer un bootloop.", "Are you really sure? The phone will reboot by itself at the end, even if a file was not flashed correctly, which can cause a bootloop."},
        {"w_slotb", "Êtes-vous vraiment sûr ? Certains appareils ne supportent pas le flashage sur différents slots et peuvent avoir des soucis.", "Are you really sure? Some devices do not support flashing to different slots and may run into problems."},
        {"w_nomd5", "Attention : si le firmware est corrompu, désactiver la vérification MD5 ignorera ce problème. Le MD5 sert justement à vérifier que le paquet a été téléchargé correctement et n'est pas altéré. Sans lui, tu peux installer un firmware corrompu ou impossible à démarrer, et causer des dysfonctionnements. L'auteur de l'app n'est pas responsable des problèmes causés par cette action.", "Warning: if the firmware is corrupted, disabling the MD5 check will ignore it. The MD5 is what confirms the package was downloaded correctly and is not altered. Without it you may install a corrupted or unbootable firmware and cause malfunctions. The app's author is not responsible for problems caused by this action."},
        {"w_erase", "Êtes-vous vraiment sûr de vouloir effacer USERDATA ? La partition userdata du téléphone sera effacée, quel que soit ce que tu flashes. Normalement elle l'est déjà quand tu flashes un firmware stock complet ; cette option force l'effacement dans tous les cas. Toutes les données seront perdues, et ça peut mal fonctionner sur certains appareils.", "Are you really sure you want to erase USERDATA? The phone's userdata partition will be wiped no matter what you flash. It is normally wiped already when you flash a full stock firmware; this option forces it in every case. All data will be lost, and it may not work well on every device."},
        {"w_repart", "Êtes-vous vraiment sûr ? Repartitionner modifie la table des partitions. Une erreur ou un mauvais fichier PIT peut rendre le téléphone inutilisable (brick).", "Are you really sure? Repartition changes the partition table. A mistake or a wrong PIT file can brick the phone."},
        {"w_tflash", "Êtes-vous vraiment sûr d'activer TFlash ? Cela effacera la carte SD du téléphone, et les partitions seront flashées dessus au lieu de la mémoire interne (eMMC).", "Are you really sure you want to enable TFlash? This will wipe the phone's SD card, and the partitions will be flashed to it instead of the internal storage (eMMC)."},
        {"themeL", "Thème clair", "Light theme"},
        {"themeD", "Thème sombre", "Dark theme"},
        {"usbinfo", "Infos USB", "USB info"},
        {"nobulk", "Le téléphone est-il bien en Download Mode CONFIRMÉ (écran « Downloading », après Volume haut) ? Débranche/rebranche le câble puis reconnecte.", "Is the phone really in CONFIRMED Download Mode (\"Downloading\" screen, after Volume Up)? Unplug/replug the cable and reconnect."},
        {"wait", "En attente de l'interface USB du téléphone (Download Mode confirmé ?)", "Waiting for the phone's USB interface (Download Mode confirmed?)"},
        {"lost", "Écriture USB impossible : connexion perdue (le téléphone s'est probablement ré-énuméré). Clique Connecter à nouveau.", "USB write failed: connection lost (the phone probably re-enumerated). Tap Connect again."},
        {"lang", "English", "Français"},
        {"share", "Partager en .log", "Share as .log"},
        {"sharetxt", "Partager en .txt", "Share as .txt"},
        {"nousb", "Aucun Samsung détecté (Download Mode + câble OTG ?)", "No Samsung found (Download Mode + OTG cable?)"},
        {"perm", "Accepte la permission USB : la connexion continue toute seule", "Accept the USB permission: the connection continues by itself"},
        {"open", "USB ouvert", "USB open"},
        {"noif", "Interface bulk introuvable", "Bulk interface not found"},
        {"conn1", "Connecte d'abord le téléphone", "Connect the phone first"},
        {"noloke", "Pas de LOKE, abandon", "No LOKE, aborting"},
        {"todo", "Envoi des partitions pas encore codé (étape 2)", "Partition upload not coded yet (step 2)"},
        {"nofile", "Aucun fichier ou fichier non reconnu", "No file or file not recognised"},
        {"analyze", "Analyser le boot", "Analyze boot"},
        {"driver", "Pilote Odin : ", "Odin driver: "},
        {"s_none", "Aucun appareil détecté. Branche le téléphone cible en OTG.", "No device detected. Plug the target phone in with OTG."},
        {"s_other", "Un appareil USB est branché, mais ce n'est pas un téléphone Samsung.", "A USB device is plugged in, but it is not a Samsung phone."},
        {"s_normal", "Samsung détecté en mode normal, sans ADB. Active le débogage USB, ou passe-le en Download Mode à la main.", "Samsung detected in normal mode, without ADB. Enable USB debugging, or put it in Download Mode by hand."},
        {"s_adb", "Téléphone détecté avec ADB. Tu peux le redémarrer en Download Mode.", "Phone detected with ADB. You can reboot it into Download Mode."},
        {"s_dl", "Download Mode détecté.", "Download Mode detected."},
        {"s_reboot", "Redémarrer en Download Mode", "Reboot to Download Mode"},
        {"s_go", "Ouvrir Mini Odin", "Open Mini Odin"},
        {"s_auth", "Si le téléphone affiche « Autoriser le débogage USB ? », accepte (jusqu'à 70 s).", "If the phone shows \"Allow USB debugging?\", accept it (up to 70 s)."},
        {"s_sent", "Commande envoyée. Le téléphone redémarre en Download Mode.", "Command sent. The phone is rebooting to Download Mode."},
        {"s_fail", "La commande n'a pas abouti. Regarde le journal.", "The command did not go through. Check the log."},
        {"b_title", "Analyse du boot", "Boot analysis"},
        {"b_pick", "Choisis un boot.img, init_boot.img, vendor_boot.img ou un firmware .tar", "Pick a boot.img, init_boot.img, vendor_boot.img or a .tar firmware"},
        {"b_busy", "Analyse en cours...", "Analyzing..."},
        {"b_none", "Aucun fichier boot trouvé dans ce fichier.", "No boot file found in this file."},
        {"b_close", "Fermer", "Close"},
        {"b_find", "Recherche de l'image du téléphone...", "Looking for the phone picture..."},
        {"b_noimg", "Pas d'image trouvée", "No picture found"},
        {"b_gimg", "Google Images", "Google Images"},
        {"b_key", "Clé Google", "Google key"},
        {"b_key_t", "Recherche Google Images : clé API et identifiant de moteur (cx) de Programmable Search. Sans eux, l'image vient de Wikipédia.", "Google Images search: Programmable Search API key and engine id (cx). Without them the picture comes from Wikipedia."},
        {"b_save", "Enregistrer", "Save"},
        {"flash", "FLASH", "FLASH"}
    };

    // Texte choisi selon la langue (pour les messages du journal)
    public static String x(String fr, String enText) { return en ? enText : fr; }

    public static String t(String k) {
        for (String[] r : T) if (r[0].equals(k)) return r[en ? 2 : 1];
        return k;
    }
    public static void load(Context c) { en = c.getSharedPreferences("p", 0).getBoolean("en", false); }
    public static void toggle(Context c) {
        en = !en;
        c.getSharedPreferences("p", 0).edit().putBoolean("en", en).commit();
    }
}
