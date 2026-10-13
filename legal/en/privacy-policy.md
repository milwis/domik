# Domik – Privacy Policy

Version 1.1 · Effective from: 13 October 2026

[Wersja polska](../polityka-prywatnosci.md) · [Terms of Use](terms.md)

*This English version is a translation. If it differs from the Polish version, the Polish version prevails, unless
mandatory law provides otherwise.*

> **In short:** The author of Domik receives none of your data. There are no accounts, analytics, ads or author
> servers. Everything the app stores stays on your phone or tablet. It controls your controller directly on your local
> network. If you use the weather tile, it sends to the Open-Meteo weather service only the place name you type (when
> searching) and the approximate coordinates of the chosen place. If you yourself add the optional eModul integration
> (TECH controllers), the app sends your API key to that service and only reads the data of your controllers; the
> author of the app still receives nothing.

Capitalised terms (e.g. App, Controller, Serial Number, Local Network, Repository, Official Source) have the meaning
given in the [Terms of Use](terms.md).

## 1. Author and contact

The App is provided by its author, who uses the pseudonym "milwis" (GitHub account: <https://github.com/milwis>) (the
"Author"). For privacy matters, you can contact the Author via the Issues tab of the Repository
(<https://github.com/milwis/domik/issues>). Issues are public – do not include the Serial Number, the Controller's IP
address or any other data you do not want to disclose. Report security vulnerabilities confidentially via the
Repository's Security advisories feature (<https://github.com/milwis/domik/security/advisories/new>).

## 2. The App collects no data

1. The App sends no data – personal or technical – to the Author. The Author runs no server for the App to connect to.
2. The App has no accounts or sign-in and contains no analytics, advertising or tracking, and no third-party libraries
   (SDKs) for such purposes. It uses only open source libraries from Google (AndroidX, Jetpack Compose) and JetBrains
   (Kotlin).
3. The Author has no access to the data stored by the App on your Device, to your calendars or to your Installation.
4. Outside your Local Network, the App sends only the data needed to show the weather – and only when you search for or
   choose a place for the weather tile (point 4(3)–(4)) – and, only if you yourself add the optional eModul
   integration, the data needed to read your controllers from the eModul service (point 4(5)). None of this goes to the
   Author.
5. The App's source code is public (the Repository), so anyone can check what data the App sends and where.

## 3. Data stored only on your Device

To work, the App stores the following on your Device, in its private storage:

| Data | Purpose | How to delete |
|---|---|---|
| Controller IP address | connecting to the Controller | "Remove saved controller" in App settings or by deleting the App |
| Serial Number – encrypted with a key held in the Android Keystore system | establishing the connection to the Controller | "Remove saved controller" in App settings or by deleting the App |
| Copy of the Controller configuration (e.g. names of rooms, zones, outputs) | displaying and controlling the Installation | by deleting the App; refreshed from the Controller |
| Dashboard layout, choice of icons and tile elements, choice of calendars shown, appearance and tablet mode settings | App features | in the App (e.g. "Restore default layout") or by deleting the App |
| Place chosen for weather and its approximate coordinates | showing the weather | "Remove town" in the App menu or by deleting the App |
| Most recently downloaded weather forecast, with its download time and the place's approximate coordinates (cache) | showing the latest weather, e.g. after a restart without Internet access | "Remove town" in the App menu or by deleting the App; replaced on every download |
| eModul API key – encrypted with a key held in the Android Keystore system (only after you add the eModul integration) | connecting to the eModul service | "Remove integration" in the App's eModul settings or by deleting the App |
| List of your eModul modules (identifiers), choice of data shown, most recently downloaded eModul responses (controller data and menus, part of the text dictionary) with their download time (cache in the App's private storage) | showing the eModul controllers' data, also after a restart without Internet access | "Remove integration" in the App's eModul settings or by deleting the App; replaced on every download |
| Version of the accepted Terms | confirming acceptance, asking again after the Terms change | by deleting the App |

1. The App reads the current status of the Installation (e.g. temperatures, output states) from the Controller to
   display it and does not send it outside your Local Network.
2. The App reads calendar events from the Device's system calendar (with your permission, point 5) only to show them
   on the tile. It does not change, store or send them off the Device.
3. You process all this data yourself – on your own Device and for your own purposes. The Author does not receive it
   and has no access to it.
4. Deleting the App deletes all data it has stored, including the encrypted Serial Number and the encryption key.
5. The App's data is not included in cloud backups and is not transferred to a new device during data transfer.
6. If the Device is accessible to others (e.g. a wall-mounted tablet), they can see what the App shows – including the
   status of the Installation and calendar events. You decide which tiles to place there.
7. Demo mode uses only sample data built into the App (including a sample calendar and weather) and connects to no
   device or server.

## 4. Network connections

1. **Controller.** The App connects to the Controller at the IP address you enter, on your Local Network. The
   connection is encrypted (TLS) and the App verifies the Controller's certificate. The security of this connection
   also depends on the Controller and its firmware, which the Author does not control. The App does not connect to the
   Controller Manufacturer's (Deimic) cloud or to the Author's servers.
2. **Calendars.** Calendar sync (e.g. a Google calendar on an account added to the Device) is performed by Android and
   the calendar providers' apps, under their terms. The App does not connect to their servers.
3. **Searching for a place.** When you type a place name for the weather, the App looks it up in the Open-Meteo
   geocoding service (<https://open-meteo.com>) as you type – once at least 2 characters are entered and you pause
   briefly. The service receives the text you type and the language code for the result names (e.g. "pl"). The App
   does not request access to your location and does not use it.
4. **Weather.** To get current weather and the forecast, the App sends the Open-Meteo service a request containing the
   approximate coordinates of the chosen place (rounded to 2 decimal places, i.e. to about 1 km) – more precise
   coordinates never leave the Device. The forecast is downloaded when you choose a place and then refreshed about
   every 30 minutes while the App is open on screen, and when you return to the App if the data is older than
   15 minutes. The connection is encrypted (HTTPS). As with any Internet connection, the service also receives your
   Device's IP address and standard technical connection data. The request contains no identifiers of the User,
   Device or Installation. Open-Meteo processes this data under its own terms, published on its website. Until you
   search for or choose a place, the App does not connect to the weather service; once you remove the place
   ("Remove town"), it stops connecting to it. In demo mode it never connects to it (point 3(7)).
5. **eModul (TECH) – optional.** This feature is off until you add it yourself ("Add eModul integration" in the App
   menu); before the first connection the App shows a notice that you must confirm.
   - *What is sent.* The App connects directly from the Device to the `emodul.eu` service (HTTPS) and sends, in the
     `Authorization` header, your API key generated in your eModul account (the key contains your eModul user name and
     account identifier). It sends read-only requests only: it downloads the list of your modules (controllers), their
     current data and menus, and the text dictionary. It changes nothing in your controllers or your eModul account.
   - *When.* Only after you add the integration and only while the Device has Internet access: controller data about
     every 60 seconds while the App's dashboard is open on screen, controller menus about every 15 minutes, the module
     list about every 6 hours and the text dictionary once a day. The App does not otherwise connect to the service. In
     demo mode it never connects to it (point 3(7)).
   - *Where it is stored.* The API key is encrypted with a key from the Android Keystore and stored only on the Device;
     the downloaded responses (cache) are kept in the App's private storage (point 3). "Remove integration" deletes the
     key, the stored eModul data and the choice of data shown, and the App stops connecting to the service.
   - *The Author receives nothing.* The data and the key go only to the eModul service – not to the Author or any
     server of the Author. The eModul service also receives the Device's IP address and standard technical connection
     data.
   - *Separate controller.* The eModul service is run by TECH STEROWNIKI sp. z o.o. sp. k. (ul. Biała Droga 31, 34-122
     Wieprz, Poland, KRS 0000594881), which is a separate data controller of your data and processes it under its own
     terms (point 8). To use this feature you need your own eModul account.
6. **Links.** Items you open in other apps (e.g. an event opened in the calendar app, a page opened in a browser) are
   subject to the terms of that app or service (point 8).

## 5. Permissions

| Permission | Purpose | Notes |
|---|---|---|
| Internet and network state | connecting to the Controller on the Local Network, downloading weather, reading data from the eModul service (after you add the integration), detecting network loss or changes | granted at installation |
| Read calendar | showing events on the calendar tile; read-only | requested only when you tap "Show my calendar"; without it the calendar tile does not show your events |
| Access to devices on the local network (if your Android version requires it) | connecting to the Controller | the current version of the App does not request this permission; if it ever needs it, only demo mode will work without it |
| Bluetooth ("Nearby devices"; on Android 11 and older also location) – only once room thermometer support is turned on | receiving the radio broadcasts of room thermometers | used only locally to read the thermometers; the App does not derive your position from them, does not connect to them and does not send this data off the Device |

You can change permissions at any time in system settings (Settings → Apps → Domik → Permissions). The App does not
request access to contacts, photos, camera or microphone, requests location only on Android 11 and older for thermometer
support (row above), and shows a notification only as the permanent entry of the running thermometer service.

The "Domik as the home screen" option (wall-mounted tablet mode) is not a permission: once you enable it and choose Domik
as the home screen in system settings, Android itself opens the App after the Device restarts.

## 6. Crash reports and statistics

1. The App has no crash reporting or statistics and sends no reports to the Author.
2. GitHub provides the Author with aggregated Repository statistics (e.g. number of release file downloads, number of
   Repository visits). The Author receives no data in them that would identify people downloading the App.

## 7. When the Author processes your personal data

Using the App does not involve the Author processing your personal data. The Author processes it only when you contact
the Author via GitHub (e.g. by creating an issue or a comment in the Issues tab, or a Security advisory report).

### 7.1 Contact via GitHub

- **Controller (GDPR):** the Author (pseudonym "milwis"), contact as in point 1.
- **Data:** your GitHub username and other information from your profile that GitHub displays with a report, the
  content of the report or comment, and attachments (e.g. screenshots).
- **Purpose and legal basis:** replying to and handling your report or complaint (Art. 6(1)(b) GDPR – steps related to
  the agreement to use the App), as well as communicating with people who contact the Author and establishing,
  exercising or defending legal claims, if any (Art. 6(1)(f) GDPR – the Author's legitimate interest).
- **Visibility:** issues and comments in the Issues tab are public and remain in the Repository as part of the
  project's history. Security advisory reports are confidential – only the Author and people invited by the Author can
  see them.
- **Retention:** until the matter is resolved, then no longer than 12 months. Correspondence concerning complaints –
  until the related claims become time-barred. At your request, the Author will delete or redact your issue or
  comment to the extent GitHub allows; you can also edit or delete your own comments yourself.
- **Recipients:** GitHub, Inc. – the provider of the service hosting the Repository; it also processes your GitHub
  account data as a separate controller, under its own terms (point 8). If data is processed outside the European
  Economic Area, this is based on mechanisms provided for in the GDPR (e.g. a European Commission adequacy decision or
  standard contractual clauses).
- **Voluntary:** contacting the Author is voluntary, but you cannot create a report without a GitHub account.
- **Do not send the Serial Number** or any other access data. Before adding a screenshot or a log, check that it does
  not show the Serial Number, the Controller's IP address, calendar events or anything else you do not want to make
  public.

### 7.2 Your rights

Regarding the data in point 7.1, you have the right to access, rectify and erase your data, restrict processing, data
portability (for data processed under the agreement) and to object to processing based on legitimate interest. You can
submit a request as described in point 1 – do not include more data than necessary.

You also have the right to lodge a complaint with the President of the Personal Data Protection Office (Prezes Urzędu
Ochrony Danych Osobowych, ul. Stawki 2, 00-193 Warsaw, Poland, <https://uodo.gov.pl>) or with the supervisory
authority in your country of habitual residence.

The Author does not make decisions based solely on automated processing, including profiling.

The Author does not hold the data described in point 3, so cannot provide or delete it – you have full control over it
on your Device.

## 8. Third-party services

- **GitHub** – the Repository, the APK installation files (Releases) and reports (Issues, Security advisories) are
  provided by GitHub, Inc., which is a separate data controller. GitHub may log the IP addresses of people visiting the
  Repository and downloading files, e.g. for security purposes, and uses its own cookies – in accordance with the
  GitHub General Privacy Statement:
  <https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement>. The Author runs no
  website of their own and uses no cookies, analytics or tracking tools of their own.
- **Open-Meteo** – provider of weather data and place search (point 4(3)–(4)), operating under its own terms:
  <https://open-meteo.com>.
- **TECH STEROWNIKI sp. z o.o. sp. k.** (ul. Biała Droga 31, 34-122 Wieprz, Poland, KRS 0000594881) – operator of the
  eModul service (<https://emodul.eu>), to which the App connects only after you add the eModul integration (point 4(5)).
  It is a separate controller of your data (including the API key and the data of your controllers) and operates under
  its own terms: privacy policy <https://emodul.eu/policy/emodul/en>, terms of service
  <https://emodul.eu/terms-of-service/emodul/pl> (Polish). The Author is not affiliated with TECH Sterowniki or the
  eModul service.
- **Google** – Android and the sync of Google calendars on the Device (point 4(2)) are subject to Google's terms;
  Google is a separate data controller: <https://policies.google.com/privacy>. The App sends Google no data.
- **Controller Manufacturer (Deimic)** – the App sends it no data. The Controller and the Controller Manufacturer's services
  (e.g. its app or cloud), if you use them, operate under the manufacturer's own terms, independently of the App.

## 9. Children

The App is not directed at children and collects no data, including children's data. Minors should use the App with
the consent of a parent or guardian and of the person entitled to the Installation. If a child has created an issue or
a comment in the Repository, a parent or guardian may request its deletion.

## 10. Changes to this Privacy Policy

1. A new version of this Privacy Policy, with its effective date, is published in the Repository (the `legal/`
   directory) and made available in the App. Previous versions are available in the Repository's change history.
2. Before changing how data is transferred (e.g. changing the weather provider) or starting to transfer any new data,
   the Author will update this Privacy Policy. The App will inform you of such a change before any data is first
   transferred and – where required by law – ask for your consent.
