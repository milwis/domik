# Domik – Terms of Use

Version 1.1 · Effective from: 13 October 2026

[Wersja polska](../regulamin.md) · [Privacy Policy](privacy-policy.md)

*This English version is a translation. If it differs from the Polish version, the Polish version prevails, unless
mandatory law provides otherwise.*

> **In short** (this summary does not replace the Terms)
>
> - Domik is a free, **unofficial** educational and hobby project with open source code (MIT licence), not affiliated
>   with the manufacturer of Deimic controllers.
> - It is not an alternative to or a replacement for the manufacturer's official app, and it is not supported or
>   approved by the manufacturer.
> - It controls your controller directly on your local network, without any cloud. The Author collects no data.
> - Optionally (only if you add it yourself) it can read your controllers' data from the eModul service of TECH
>   Sterowniki. That is an independent service with its own terms; the Author is not responsible for its operation or
>   data.
> - You use it at your own risk and without warranty. It must not be the only safeguard for heating, frost protection,
>   pumps or blinds.
> - Only control your own installation, or someone else's with the owner's permission. Protect your controller's
>   serial number.
> - Download the app only from the official source: <https://github.com/milwis/domik/releases>.
> - Contact the author only via GitHub: <https://github.com/milwis/domik/issues>.
> - These Terms do not limit the rights you have as a consumer under the law.

## § 1. Author and contact

1. The App is provided by its author, who uses the pseudonym "milwis" (GitHub account: <https://github.com/milwis>) –
   a private individual acting free of charge and outside any business activity (the "Author").
2. The Author can be contacted via GitHub:
   1. questions, bug reports and complaints – in the Issues tab of the Repository:
      <https://github.com/milwis/domik/issues>;
   2. security vulnerability reports – confidentially, via the Repository's Security advisories feature:
      <https://github.com/milwis/domik/security/advisories/new>.
3. These Terms are made available free of charge before the App is first used – in the Repository
   (<https://github.com/milwis/domik/blob/main/legal/regulamin.md>; English translation:
   <https://github.com/milwis/domik/blob/main/legal/en/terms.md>) in a form that allows them to be downloaded, saved
   and printed, and in the App – on the acceptance screen shown on first launch (a summary with the address of the full
   text).

## § 2. Definitions

- **App** – the Domik software for Android devices, including updates.
- **User** – any person using the App.
- **Consumer** – a User who is a natural person using the App for purposes not directly related to their business or
  profession. Provisions concerning Consumers also apply to a natural person concluding a contract directly related to
  their business activity where the contract shows that it is not of a professional nature for that person.
- **Controller** – a 1st-generation Deimic building automation controller with which the App works.
- **Controller Manufacturer** – the entity that manufactures the Controller or places it on the market under the Deimic
  brand, and its affiliates.
- **Installation** – the installation operated by the Controller, including connected equipment (e.g. lighting,
  heating, sockets, pumps, blinds).
- **Serial Number** – the Controller's serial number, required to connect to it.
- **Local Network** – the network (LAN / Wi-Fi) to which the Controller is connected.
- **Device** – the Android smartphone or tablet on which the User uses the App.
- **Repository** – the public project repository maintained by the Author on GitHub:
  <https://github.com/milwis/domik>, containing the App's source code, these Terms and the Privacy Policy.
- **Official Source** – where the Author makes the App available: the Releases section of the Repository
  (<https://github.com/milwis/domik/releases>), including the APK installation files published there. The App is not
  currently available on Google Play.
- **Terms** – these Terms of Use.

## § 3. Nature of the App

1. The App is an unofficial, independent project of the Author. **It is not a product of the Controller Manufacturer,
   is not affiliated with it, and is not authorised, sponsored, approved or supported by it.**
2. The App is an educational and hobby project, intended for people developing an interest in home automation (smart
   home) based on Controllers. **It is not an alternative to or a replacement for the official app or any other software
   of the Controller Manufacturer** and is not intended to replace them.
3. The Controller Manufacturer is not responsible for the App and provides no support for it. Questions and reports
   about the App should be sent only to the Author (§ 1(2)).
4. The App was written from scratch, independently of the Controller Manufacturer's software, in order to interoperate
   with the Controller. It contains no software of the Controller Manufacturer.
5. "Deimic" and other designations of the Controller Manufacturer may be trademarks of their owners. They are used in
   the App, its description and the Repository only descriptively – to indicate which devices the App works with.
   Android is a trademark of Google LLC, GitHub is a trademark of GitHub, Inc., and "eModul" and "TECH" are
   designations of TECH STEROWNIKI sp. z o.o. sp. k. All trademarks belong to their owners.
6. The App is free. It contains no advertising and no in-app purchases.
7. The App's source code is publicly available in the Repository under the MIT licence (§ 7(1)).
8. The App is not affiliated with TECH STEROWNIKI sp. z o.o. sp. k. ("TECH Sterowniki") or with the eModul service,
   and is not authorised, sponsored or supported by them. The optional integration with the eModul service (§ 4(6))
   uses an interface provided by that service.

## § 4. Features

1. To the extent permitted by the Controller and its configuration, the App allows in particular:
   1. reading the status of the Installation (e.g. temperatures, status of lights and other outputs);
   2. controlling heating (e.g. changing the set temperature or profile in zones);
   3. switching lights and other outputs on and off;
   4. controlling blinds (raising, lowering, stopping);
   5. arranging a personal dashboard of tiles, including use as an always-on tablet (e.g. wall-mounted);
   6. showing events from calendars available on the Device – read-only and with the User's permission;
   7. showing current weather and the forecast for a place chosen by the User;
   8. optionally – showing (read-only) the data of TECH controllers from the eModul service, after the User adds the
      eModul integration (section 6);
   9. a demo mode using sample data, without connecting to any device.
2. To control the Installation, the App connects directly to the Controller on the Local Network (TLS-encrypted
   connection). It does not use the cloud of the Controller Manufacturer (Deimic) or any servers of the Author. The App
   connects to the Internet only to search for the chosen place and to download the weather forecast from an external
   service (currently Open-Meteo) and – only if the User adds the eModul integration themselves (section 6) – to read
   data from the eModul service. See the Privacy Policy for details.
3. The App reads the Controller's configuration (e.g. names of rooms, zones and outputs) in order to display it. It
   does not upload firmware to the Controller and does not change the configuration stored in it.
4. Weather, calendar and eModul data come from third parties and are for information only. They must not be relied on
   for decisions affecting the safety of people, animals or property. Weather data is provided under its provider's terms,
   indicated in the App (the "Weather" section of the menu).
5. Features may change in future versions of the App (§ 13). The current description of features is available in the
   Repository and in the App itself.
6. **eModul integration (optional).** The feature is off until the User adds it in the App and confirms the connection
   notice. Once added, the App connects directly from the Device to the eModul service (<https://emodul.eu>) and – using
   an API key the User generated in their eModul account – only reads the data of their controllers; it changes nothing
   in them or in the eModul account. The eModul service is run by an entity independent of the Author, TECH Sterowniki,
   which acts under its own terms, including the service's terms of service
   (<https://emodul.eu/terms-of-service/emodul/pl>, Polish). The User needs their own eModul account and is responsible
   for using it in line with those terms and for protecting their API key. The Author is not responsible for the
   availability or operation of the eModul service or for the correctness, timeliness and completeness of data coming
   from it (§ 11(3)). The User can remove the integration at any time in the App's settings; this deletes the API key
   and the stored eModul data.

## § 5. Technical requirements

1. To use the App (other than in demo mode) you need:
   1. a 1st-generation Deimic Controller – the App is not intended to work with other controllers;
   2. a Device running Android 8.0 or later;
   3. the Device connected to the Local Network on which the Controller operates, and permission for the App to access
      the local network, if the system requires it;
   4. the Controller's IP address on the Local Network (a fixed address is recommended, e.g. a DHCP reservation in the
      router) and the Serial Number;
   5. for the weather tile – Internet access; for the calendar tile – permission to read the calendar; for the
      (optional) eModul integration – Internet access, and your own eModul account and API key.
2. Installing from an APK file requires allowing app installation from the chosen source in Android settings.
3. Remote access to the Local Network (e.g. via VPN) is set up and secured by the User, at the User's own
   responsibility.
4. The Author does not guarantee that the App will work with every firmware version and configuration of the
   Controller or on every Device. The Controller Manufacturer may change the firmware in a way that limits or prevents
   the App from working.

## § 6. Conclusion and termination of the agreement

1. The agreement to use the App under these Terms is concluded when the User accepts the Terms on the screen displayed
   when the App is first launched. The agreement is free of charge and concluded for an indefinite period.
2. The User may terminate the agreement at any time, without giving reasons and at no cost, by deleting the App from
   the Device. Deletion of data stored by the App is described in the Privacy Policy.
3. The rules for the Author ending development and distribution of the App are set out in § 13.

## § 7. Licence

1. The App – both its source code and the APK installation files published at the Official Source – is made available
   under the MIT licence, the text of which is in the Repository (the LICENSE file). The MIT licence allows in
   particular using, copying, modifying and distributing the App, under the conditions set out in it (including
   keeping the copyright notice and the licence text). **These Terms do not limit the rights granted by the MIT
   licence.**
2. These Terms set out the rules for using the App to control the Installation, including the User's obligations and
   safety-related limitations (§§ 8–10), and the rules on the Author's liability (§ 11).
3. Use of GitHub (including browsing the Repository and downloading files from it) is subject to GitHub's terms. They do
   not change these Terms in the relationship between the User and the Author.
4. The User may share a link to the Official Source or to the Repository with others.
5. The User may not:
   1. use the App unlawfully or in breach of these Terms, in particular § 8;
   2. distribute modified versions of the App under the name "Domik" or in any other way that is misleading as to their
      origin, in particular suggesting that they come from the Author;
   3. present the App or modified versions of it as a product of the Controller Manufacturer;
   4. send the Author unlawful content (e.g. in reports in the Repository).
6. Section 5 does not limit any rights the User has under mandatory provisions of law or under the MIT licence referred
   to in section 1.
7. Copyright in the App belongs to the Author, except for third-party components listed in the Repository (if any).

## § 8. Authorisation to control the Installation

1. By connecting the App to a Controller, the User declares that they own the Installation or are entitled to use it
   (e.g. as a household member or tenant), or act with the consent of such a person.
2. It is prohibited to use the App to connect to another person's Controller or Installation without the consent of
   the entitled person, including by using a Serial Number obtained without their knowledge. Such conduct may violate
   the law, including criminal law.

## § 9. User obligations and security

1. **The Serial Number works like a key to the Installation.** Anyone who knows it and has access to the Local Network
   can read the status of the Installation and control it – even without the App. The User should therefore:
   1. not disclose the Serial Number to unauthorised persons – including on forums, in screenshots and in reports in
      the Repository (reports in the Issues tab are public);
   2. secure the Local Network: a strong Wi-Fi password, up-to-date router firmware, a separate guest network, and no
      exposure of the Controller to the Internet (e.g. via port forwarding);
   3. protect the Device (screen lock, system updates). Anyone with access to an unlocked Device can control the
      Installation. A Device permanently accessible to others (e.g. a wall-mounted tablet) lets anyone nearby control
      the Installation – whether to use it this way is the User's decision.
2. **Download the App only from the Official Source.** An installation file from another source may have been modified
   and contain malware capable of taking control of the Installation. If the Author publishes a checksum of the file,
   it is worth verifying it.
3. **Physical controls (switches, panels) and the Controller Manufacturer's tools remain the primary way to operate the
   Installation.** The User should keep them in working order and know how to use them, including in case the App is
   unavailable.
4. Using software and networks involves typical risks, including unauthorised access to the Local Network or the
   Controller, malware, interception of the Serial Number and impersonation (phishing). **The Author never asks for
   the Serial Number or passwords.**

## § 10. Critical functions – important limitations

1. **The App must not be the only or primary safeguard for functions on which the safety of people, animals or property
   depends**, such as frost protection, pumps, heating, blinds and other drives. Such functions should be secured
   independently of the App (e.g. in the Controller's configuration, by the installer, or with separate devices).
2. Commands – including commands executed automatically (e.g. schedules or automatic switch-off), if the App offers
   such features – may not be executed, or may be executed with a delay, in particular when:
   1. the Device is not connected to the Local Network;
   2. the operating system or power-saving features have suspended, restricted or closed the App;
   3. the Device is switched off, its battery is flat, or it is restarting or updating;
   4. the Local Network, power supply or Controller fails;
   5. the device executing rules (e.g. a wall-mounted tablet or a home server) is not working, if the User uses one;
   6. the Controller Manufacturer has changed the Controller's firmware.

   Commands may also fail due to an error in the App.
3. Automatic features executed by a Device (e.g. a wall-mounted tablet), if the App offers them, work only while that
   Device is switched on, connected to the Controller and the App is running on it.
4. The status of the Installation shown in the App may differ from the actual status, e.g. due to delays or loss of
   connection. Where this matters for safety, the User should check the status on site.
5. Before remotely operating blinds, drives or heating devices, the User should make sure this will not endanger people
   or animals.
6. The App may limit the range of settings (e.g. temperature) for safety reasons. This does not release the User from
   assessing the consequences of the commands they send.

## § 11. Disclaimer of warranties and liability

1. The App is provided free of charge, "as is". To the extent permitted by law, the Author gives no warranties or
   assurances, in particular that the App will work uninterrupted and error-free, with every Controller, every firmware
   version and on every Device, or that it will meet the User's expectations.
2. Given that the App is free of charge, the Author is liable for non-performance or improper performance of the
   agreement only for damage caused intentionally or through gross negligence – subject to section 4.
3. To the extent permitted by law and subject to section 4, the Author is not liable for damage resulting from:
   1. incorrect configuration of the Controller or the Installation;
   2. failure or unavailability of the Local Network, the Internet, the power supply or the Device;
   3. changes to the Controller's firmware made by the Controller Manufacturer;
   4. acts or omissions of the Controller Manufacturer, Google, GitHub, TECH Sterowniki (including unavailability,
      changes or discontinuation of the eModul service) or other third parties for whom the Author is not responsible;
   5. disclosure of the Serial Number or use of the App in breach of these Terms, in particular §§ 8–10;
   6. commands not being executed, or being delayed, for the reasons listed in § 10(2) points 1–6;
   7. inaccuracy or unavailability of third-party data (e.g. weather forecasts, calendars, the eModul service);
   8. use of versions of the App obtained outside the Official Source or modified by third parties;
   9. the technical condition, mounting and power supply of the Device (e.g. a tablet permanently mounted on a wall).
4. **Nothing in these Terms excludes or limits the Author's liability:**
   1. for damage caused intentionally or through gross negligence;
   2. for personal injury (death, bodily injury, damage to health);
   3. towards a Consumer – to the extent this would conflict with consumer protection law, in particular the rules on
      unfair contract terms;
   4. in other cases where exclusion or limitation of liability is not permitted under mandatory law (e.g. product
      liability rules, where applicable).
5. Towards Users who are not Consumers, the Author is not liable for lost profits, to the extent permitted by law.
6. If the provisions of the Polish Consumer Rights Act on the supply of digital content apply to the agreement, the
   Consumer is entitled to the rights arising from them, and these Terms do not limit those rights.

## § 12. Controller Manufacturer's warranty

1. The App does not upload firmware to the Controller and does not change the configuration stored in it. It only
   sends read and control commands (e.g. read status, switch an output on or off, set a temperature or heating
   profile, raise, lower or stop a blind).
2. The terms of the Controller's warranty (guarantee) are set by the guarantor (e.g. the Controller Manufacturer). The
   Author has no influence on how the guarantor will assess use of the App. The User should review the warranty terms
   and assess whether using the App may affect them.
3. If the Controller or the Installation malfunctions, it is worth checking – before reporting it to the guarantor or
   installer – whether the problem also occurs without the App (e.g. when using switches or the Controller
   Manufacturer's software).

## § 13. Updates and end of development

1. The Author develops the App free of charge, as time permits, and may release updates that change, add or remove
   features – in particular to fix errors, improve security or adapt to changes in Android or the Controller. The
   Author recommends installing updates, as they may contain security fixes.
2. The App does not update itself. The User downloads new versions from the Official Source; the Author announces new
   versions in the Repository (the Releases section).
3. The Author may suspend or end development of the App, stop providing the installation file or archive the
   Repository at any time. This does not prevent the User from using an already installed copy of the App, but may
   prevent it from being downloaded again and from receiving updates. Where possible, the Author will announce the end
   of development in the Repository.
4. The App may stop working in whole or in part due to changes beyond the Author's control, in particular changes to
   the Controller's firmware or Android.

## § 14. Complaints and contact

1. Questions, bug reports and complaints about the App can be submitted in the Issues tab of the Repository
   (<https://github.com/milwis/domik/issues>). Reports in the Issues tab are public – anyone visiting the Repository can
   see them. Security vulnerabilities should be reported confidentially via the Repository's Security advisories
   feature (<https://github.com/milwis/domik/security/advisories/new>), not in public Issues.
2. To help handle a report, please include: a description of the problem and how to reproduce it, the App version, the
   Device model and system version and, if possible, the Controller's firmware version. A complaint will be handled
   even if this information is missing.
3. **Do not send the Serial Number** or any other access data. The Author never asks for them. Before attaching a
   screenshot or a log to a report, it is worth removing the Serial Number, the Controller's IP address and any other
   information the User does not want to make public.
4. The Author responds to a complaint within 14 days of receiving it – in the same report in the Repository.
5. As the App is free of charge, handling a complaint consists in particular of explaining the problem, suggesting a
   workaround or fixing the error in a future version of the App. This does not limit the Consumer's statutory
   rights.
6. Matters relating to GitHub accounts and the operation of GitHub are handled by GitHub, Inc.

## § 15. GitHub

1. The agreement to use the App is concluded between the User and the Author. GitHub, Inc. and its affiliates are not
   parties to it and are not responsible for the App or its content – also where the App was downloaded from the
   Repository.
2. GitHub has no obligation to provide any maintenance or support services for the App. Reports and claims about the
   App should be addressed to the Author (§ 14).

## § 16. Privacy

The App sends no data to the Author. Data sent outside the Local Network is used to show the weather and – only if the
User adds the optional eModul integration themselves – to read the data of their controllers from the eModul service
(including the User's API key, which goes only to that service). See the [Privacy Policy](privacy-policy.md) for
details.

## § 17. Changes to the Terms

1. The Author may change these Terms for important reasons, namely:
   1. changes in law, a court ruling or an authority's decision affecting the Terms;
   2. changes to Android requirements or GitHub's terms;
   3. changes to the App's features;
   4. the need to ensure the security of Users or Installations;
   5. changes to the Controller or its firmware affecting how the App works;
   6. changes to the Author's contact details or the Official Source, including changes to the terms or scope of
      operation of external services the App uses;
   7. the need to remove ambiguities, errors or typos.
2. A new version of the Terms, with its version number and effective date, is published in the Repository (the
   `legal/` directory) and made available in the App. Changes do not apply retroactively.
3. Changes affecting the User's rights or obligations bind the User from the moment they are accepted. The App shows
   the new version of the Terms for acceptance on the first launch after the update; until then, the previous version
   applies. A User who does not accept the changes may terminate the agreement at any time at no cost by deleting the
   App.
4. Changes that do not affect the User's rights or obligations (e.g. a change of contact details, editorial
   corrections) take effect on the date the new version is published.
5. Previous versions of the Terms are available in the Repository's change history, including tagged releases.

## § 18. Governing law and disputes

1. The agreement and these Terms are governed by Polish law. This choice of law does not deprive a Consumer of the
   protection afforded by the mandatory provisions of the law of the country of their habitual residence.
2. Disputes are resolved by the court having jurisdiction under applicable law. These Terms do not change the statutory
   jurisdiction of courts in cases involving Consumers.
3. A Consumer may use out-of-court dispute resolution, including:
   1. free assistance from a municipal or district consumer ombudsman (rzecznik konsumentów) or a consumer
      organisation (e.g. Federacja Konsumentów) – in Poland;
   2. proceedings before the provincial inspector of the Trade Inspection (Inspekcja Handlowa) or a permanent consumer
      arbitration court – in Poland;
   3. in cross-border disputes within the EU – the European Consumer Centre in the Consumer's country.

   Information on these options and the list of authorised bodies in Poland is published by the President of the
   Office of Competition and Consumer Protection (UOKiK, <https://uokik.gov.pl>). Participation in out-of-court
   proceedings is voluntary for both parties.
4. In case of any problem, the Author encourages Users to report it in the Issues tab of the Repository first
   (<https://github.com/milwis/domik/issues>).

## § 19. Final provisions

1. These Terms were drawn up in Polish. The English version is a translation; in case of discrepancies the Polish
   version prevails, unless mandatory law provides otherwise.
2. If any provision of these Terms is invalid or ineffective, the remaining provisions remain in force. The invalid
   provision is replaced by the relevant provisions of law.
3. Matters not governed by these Terms are governed by Polish law, in particular the Civil Code, the Act on Copyright
   and Related Rights and – to the extent applicable – the Consumer Rights Act and the Act on Providing Services by
   Electronic Means.
