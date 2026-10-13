---
title: Paddock privacy policy
permalink: /privacy
---
<!--
  NOT TO BE PUBLISHED YET. Do not enable GitHub Pages for this folder, give this page's address to the Play Console, or ship a build whose About row opens it,
  until the section "The Google Play build and its billing library" is filled from the Play measurement (Phase 16, slice 7, docs/privacy-checklist.md)
  and the fields in [SQUARE BRACKETS] (effective date, developer, support email) are filled by the publisher. Remove this comment when they are.
  Every factual sentence below is checked against the code or the repository documents named for it in docs/privacy-checklist.md.
-->

# Paddock privacy policy

Effective date: [DATE, set when this page is published]

## What Paddock is

Paddock is an Android app for watching and controlling the coding agents that run in herdr, a terminal workspace manager, on a computer you own or administer. It talks to that computer over SSH. There is no Paddock server and no Paddock account. Paddock's own code sends nothing to the developer: not your commands, prompts, files, keys, machine addresses or alerts. This policy describes Paddock's own code, which is open source under the Apache License 2.0 at github.com/tuthan/paddock-android. The one part that is not Paddock's own code, the billing library in the Google Play build, has its own section.

## Your machine and the SSH connection

Paddock connects to the machine you add, as the user you name, over SSH. The first time, it shows you the machine's host key and pins it only if you trust it; a key that later changes is not accepted until you decide. SSH encrypts everything on the connection, and it goes to your machine and nowhere else: the herdr commands that list and read your agents, the output Paddock shows, the prompts, keys and answers you send, the files you attach or fetch, and, if you turn on locked-phone alerts, the script that installs the alert relay on your machine.

## Finding, pairing and waking a machine on your network

Three features use your local network instead of SSH. Find on this network, which you start yourself, connects to SSH servers on your network to read the banner they show. When the machine you watch stops answering at a local-network address, Paddock looks for it by name: it asks your network's name resolver and listens for mDNS announcements. Only if you turned on Search the network if it moves for that machine does it also connect to the SSH servers on that network to read their host keys. Paddock never signs in to them, and a managed network may log the short connection. Pairing with a desktop sends this phone's public key, and nothing else of yours, to a short-lived listener the desktop opens; that exchange is not encrypted, which is why you compare the key's fingerprint on both screens before the desktop approves it. Wake sends a Wake-on-LAN packet, which holds the machine's hardware address, to your network's broadcast address or, over a VPN, to a relay address you saved. None of this is sent to the developer.

## The phone key and imported keys

Paddock makes its SSH key on the phone: an EC P-256 key pair in Android's Keystore, in the phone's StrongBox chip or trusted hardware where the phone has either. Add machine says where this phone's key is held, and says so when it is software only. The private key cannot be exported and can only sign. Only the public key leaves the phone, when you copy, share or show it or send it through pairing, so that your machine will accept it. Uninstalling Paddock deletes the key; to revoke it sooner, delete its line from the machine's authorized_keys. If you import your own private key instead, Paddock stores it in its private storage on the phone, wrapped with an AES-256 key held in Android's Keystore, together with its passphrase if it has one, because connecting never asks for it. An imported key is used only to sign in to your machines over SSH and is never sent anywhere.

## Alerts

Alerts are optional and set up for each machine from that machine's page. Turning them on runs a script over SSH that installs a small relay on your machine, which reads no agent output; the relay, not your phone and not the developer, sends each alert. In the first way, a UnifiedPush app on your phone (the ntfy app can be one) gives Paddock a push address, the relay posts to it, and Paddock raises a generic notification, "An agent needs your attention." In the second way the ntfy app shows the alert: the relay posts to a topic on the ntfy server you choose, the public ntfy.sh or your own, and Paddock makes the topic from 24 random bytes on your phone. In the second way the server sees the topic, a title that contains the machine's name as you named it, a fixed text, and a link holding the machine's id, the session, the terminal and pane ids, whether the agent is blocked or done, a time and a sequence number. In the first way it sees the push address and a short message with the machine's id and a random number. Neither shows an agent's name, a prompt or any output. Any server also sees when alerts happen and the IP addresses of your machine and your phone. On the public ntfy.sh, anyone who learns a topic can read what is sent to it, and ntfy.sh is run by a third party. Paddock's own code never connects to it; the relay on your machine does. The lock screen shows only the generic words unless you change that setting. Choose a neutral machine name, because it is in the title.

## The camera

Paddock asks for the camera permission only when you use it, never at launch, and for two things. The scanner reads the pairing code a desktop shows: frames are decoded in memory and are never kept, nothing is recorded, saved or sent, and the camera is open only while the preview is on screen. Take a photo, in the composer, asks your phone's camera app for one picture, which lands in Paddock's private cache; Paddock makes one copy to send, deletes the camera's file as soon as that copy exists, and sends the copy to your machine only when you send the prompt. Every JPEG, PNG, HEIC or AVIF image, taken, picked or shared, is rewritten before it is sent so that it keeps only what draws it and its orientation. Location, camera and phone details and other embedded data are dropped, and an image Paddock cannot clean is refused, not sent.

## Files

You choose every file. You can attach one file to a prompt (from the photo picker, the document picker or the camera), share an image, a PDF or text to Paddock from another app, or fetch a file an agent made: up to 25 MB goes to the machine and up to 50 MB comes to the phone. A file you pick or share is copied once into Paddock's private cache, and that copy is what is sent. It is deleted when the prompt that names it is accepted, and anything left behind is removed the next time Paddock starts, once no draft holds it or it is a day old. Files go to your machine over the SSH connection, into the agent's folder or the drop folder you chose, and nowhere else. A PDF or a text file is sent exactly as it is. A file fetched from your machine stays in Paddock's cache until it is a day old and is then removed at the next start; if you save it or share it yourself, it is then in the place or app you chose. Removing a machine deletes the files fetched from it.

## The clipboard

Paddock writes to the clipboard only when you tap a Copy action. Copies of agent output are marked sensitive while Protect sensitive screens is on, which is the default. Copies of the alert setup script and of the ntfy subscribe link, which hold a topic and sometimes a token, are always marked sensitive, and Paddock tries to clear them from the clipboard when it next comes to the front, if they are still there. Other copies, such as your public key, a command or a file path, are not secrets and are not marked. The mark makes Android 13 and later hide the preview in its confirmation and asks keyboards and clipboard managers to keep the copy out of their history; it is a request, and an app may ignore it. Apart from what you paste into a field yourself, Paddock reads the text on the clipboard in two cases only: when you tap Paste a pairing link, and after you import a private key you pasted, when it checks whether the clipboard still holds a private key and, if so, clears it.

## What stays on the phone

Everything Paddock keeps is in its private storage on the phone. Android backup and device transfer are switched off for Paddock, so a restored or new phone starts empty and needs a new key and its machines again. Paddock keeps the machines you saved (name, address, port, user, and what it read to wake them: the hardware address and any relay you saved), the host keys you trusted, your imported key if you have one, your settings and snippets, a record of what the phone sent to each agent (a fingerprint of each prompt, not its text, unless you turn on Keep prompt text; for a file, its path on the machine, size and fingerprint), a log of the agent state changes the phone saw (no prompt or output text; at most 30 days or 5,000 rows), each machine's alert setup (the way, the server, the topic and any access token), and a small cache for the home-screen widgets (counts, the machine's name and the titles of up to two blocked agents). Agent output is shown and is not saved. Removing a machine deletes its profile, host key, log entries, alert registration, widget copy and fetched files, and keeps the record of sends; uninstalling Paddock deletes everything. The developer holds none of it, so there is nothing to ask the developer to delete.

## Permissions

Paddock asks Android for six permissions: internet, network state (to notice a change of network and replace a dead connection), notifications (asked when you turn alerts on), local network access (Android 17 and later require it to reach a machine by its LAN address), the camera (above), and biometric (declared only so that Android's own screen-lock prompt can open for Lock Paddock; Paddock never reads a fingerprint or a face).

## No analytics, no crash reporting

Paddock's own code contains no analytics, advertising, attribution or crash-reporting library and uploads no logs or crash reports. The release build is checked for this before it is published.

The demo machine ("Try a demo") runs entirely inside the app: it opens no connection and reads nothing from your phone, and its machine is never saved. Using it leaves the same few records any machine would (the fingerprint of a prompt you send it and a log of its agents' state changes), which are deleted when you remove the demo, or the next time Paddock starts.

## The Google Play build and its billing library

[PLACEHOLDER. Not to be published until the Play measurement exists (Phase 16, slice 7). This paragraph will say, from the measurement and nothing else: which network addresses Paddock's process opened beyond your machine while Google's Play Billing library was used, or that it opened none; what that library collects and why, in Google's own words, with a link to Google's privacy policy; and what Paddock keeps on the phone about a purchase.]

## Children

Paddock is a tool for people who run their own servers. It is not directed at children or at anyone under 18.

## Changes to this policy

If this policy changes, the new text replaces this page and the effective date at the top changes. Earlier versions are kept in the project's git history.

## Contact

[DEVELOPER NAME AND PUBLIC DETAILS, the same as the Google Play Console shows]

[SUPPORT EMAIL]
