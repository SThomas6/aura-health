**Mobile Development 2025/26 Portfolio**
# Requirements

Student ID: `C24053582`

## Priority levels

Each requirement carries a MoSCoW tag indicating the development order, where I built the higher-priority requirements first.

* **[M] Must have**, which is core to the app's value and release-blocking
* **[S] Should have**, which is important although the app is still useful without it
* **[C] Could have**, which is a stretch feature that rounds out the experience

The keywords shall and should, will be used in the requirements with shall being mandatory behaviours and should indicating a strong recommendation. 

## General data management conventions

Unless stated otherwise, any feature that stores user data will have the below behaviours:

* The user can tap on any saved entry to see its full details
* The user can edit all saved entries
* The user can delete all saved entries and must confirm the delete
* Should any entry be invalid, the app will display a message showing all invalid fields

These behaviours are not repeated in each feature below. Should any feature deviate from this pattern, an explicit explanation will be given.

## Functional requirements

### Symptom logger

FR-SL-01 [M] When users are logging a symptom, they shall be able to log the symptom type from a predefined list or by typing in a custom symptom when no predefined type is available. 

FR-SL-02 [M] The user shall have the option to edit the date and time with it being filled with the current date and time by default. 

FR-SL-03 [M] Users shall have the option to log the end date and time of their symptoms if it has ended. 

FR-SL-04 [M] There shall be a slider for the severity of a symptom from 1 to 10 with 10 being the most severe and 1 being the least with a clearly displayed number. 

FR-SL-05 [M] Users shall be able to record any medication they're taking in free text field

FR-SL-06 [M] Users shall be able to tag extra context to their symptoms from a predefined list such as stress, poor sleep and alcohol.

FR-SL-07 [M] Users shall be able to attach their device location to a symptom log, using location services, if they've granted permission to do so. 

FR-SL-08 [M] Symptom logs must contain a symptom type, description as well as a valid start date and time.

FR-SL-09 [M] Users shall be able to view their symptoms in chronological order and be able to tap each symptom to show all stored details. 

FR-SL-10 [M] If the user edits a symptom log, the original date time and environmental data shall be kept the same and not fetched again. 

FR-SL-11 [S] The user should be able to attach up to three photos to their symptom log, either from the device camera or gallery, so that visual evidence can be added to the PDF to be reviewed by a doctor. 

FR-SL-12 [M] If location is saved with the symptom log the app shall get the weather, air quality index, temperature, humidity and barometric pressure from the API. 

### AI-powered analysis

FR-AA-01 [M] Users shall be able to link logged symptoms to their condition so that it is ignored in the AI analysis.

FR-AA-02 [M] Upon successful analysis the result shall produce a guidance level on a new detailed screen such as monitor or seek a doctor's advice with a list of identified correlations and contributing factors.

FR-AA-03 [M] Each analysis run should show on an AI analysis page in chronological order with the result of the analysis and run timestamp displayed.

FR-AA-04 [S] The user should be able to access an AI analysis page where they can enter additional information and trigger AI analysis of the symptom data, additional information and environmental data. 

### Doctor visits

FR-DV-01 [S] When logging a doctor's appointment, users shall be able to clear any symptoms so that those symptom logs are excluded from any future AI analyses. 

FR-DV-02 [S] When logging a doctors appointment, users shall be able to log the visit date, doctor's name, and additional details from the appointment in a free text box.

### Health conditions

FR-HC-01 [S] Should the user have any existing conditions such as asthma, cancer or diabetes, they should be able to record them either in the onboarding process or from the settings screen with the option to remove or add conditions.

FR-HC-02 [S] Any symptoms related to an existing condition shall be grouped together under the name of that condition in the symptom log history page, so that they can be found easily. 

### Medication reminders

FR-MR-01 [C] Medication reminders should have a frequency element such as daily, weekdays or specific days of the week, along with a time of day that the reminder shall fire, with each reminder having an edit or delete option. 

FR-MR-02 [C] A notification shall be sent when a reminder is fired with the option for the user to select "Taken" or "Snooze 15 min" on the notification itself. 

FR-MR-03 [C] All reminders shall have the option to be paused from a toggle in the settings screen, without the reminders being deleted. 

### Health Connect integration

FR-HK-01 [S] The user shall be able to connect to Health Connect, and grant read permissions for each metric, including steps, heart rate, sleep, weight and calories burnt.

FR-HK-02 [S] Any health connect metric that has been connected shall be passed to the AI analysis as aggregated values only with 24 hour windows for each log with 7 day rolling totals. 

### Trend visualisation

FR-TV-01 [S] The user shall be able to view a graph of a chosen symptom with environmental, and health connect data overlays, plotted on the same time axis to make correlations easier to see, viewable on a 1 day, 1 week, 1 month, 6 months, 1 year graph. 

### PDF health report

FR-RP-01 [S] The user should be able to generate a PDF health report of their symptoms, photos and AI analysis that can be shown to their doctor. 

FR-RP-02 [S] The PDF report should include all records in chronological order with a summary section at the bottom showing all symptom entries and the average severity of all symptoms. 

FR-RP-03 [S] The app should allow the user to preview the report before exporting it. 

FR-RP-04 [S] The app shall allow the user to share the PDF report to their app of choice.

FR-RP-05 [S] The app should have a history of reports in chronological order with the export timestamp where the user can open share and delete a report.

### Notifications

FR-NO-01 [S] The user should be able to enable notifications for analysis results. Should the user not want notifications anymore they should disable them in the device system settings. 

FR-NO-02 [S] When AI analysis is complete the app should send a notification saying if they're in the clear or should get the advice of a doctor and say that the result is available. Tapping the notification should open the result in the app. 

## Non-functional requirements

### Performance and responsiveness

NFR-PE-01 [M] Environmental data should be retrieved or time out within 5 seconds to ensure symptom logging stays responsive. Should it time out an error must be displayed.

NFR-PE-02 [M] AI analysis shall run as a background task so that the user isn't blocked waiting for it to complete. The app should show an AI analysis running state until the analysis is complete and push a notification when the result is ready, allowing the user to use another app on their phone in the meantime. Should the analysis fail the user must be informed without the app crashing. 

NFR-PE-03 [M] The app should launch and show the biometrics prompt within 2 seconds on a pixel 3a without any other application open. 

NFR-PE-04 [M] Symptom log entries should be saved within 1.5 seconds of the user submitting them, with a confirmation message once saved.

### Security and privacy

NFR-SE-01 [M] All user data must be encrypted and stored on device to keep the data secure.

NFR-SE-02 [M] Device location data for environmental data retrieval must only be stored as an approximate by storing the coordinate rounded to two decimal places.

NFR-SE-03 [M] All personally identifiable information should be removed before sending data to the AI API to be analysed, including names and date of birth. An approximate age range should be sent to the API instead for analysis.

NFR-SE-04 [S] The user shall be able to protect the app behind their device's biometric authentication, with device pin as a fallback. The app should lock each time the app is brought back to the foreground. 


NFR-SE-05 [S] Photo attachments shall be encrypted and any embedded EXIF metadata such as GPS tags shall be stripped before the file is written to storage. 

### Resource management and reliability

NFR-RE-01 [C] All PDF reports generated by the app must be stored on the device and should be compressed for storage efficiency

NFR-RE-02 [M] Symptom logging, doctor visit logging, and viewing of symptoms should still be available to the user when there is no internet access. Should the user request AI analysis without an internet connection they should be shown a no network error. 

NFR-RE-03 [M] API response errors should not crash the app, and the user should be notified of the error. 

NFR-RE-04 [M] Location should only be fetched when logging saving a symptom and should never be active in the background.

NFR-RE-05 [M] If there is no connection to the internet, the UI flow should not be blocked from the user when fetching environmental data from the API, so that the user would still be able to log a symptom without any environmental data attached to it.

### Accessibility and usability

NFR-UA-01 [M] Android's design guidelines must be followed, with no buttons being smaller than 48x48dp, to ensure that the app is accessible.

NFR-UA-02 [M] Both light and dark mode should be supported in the app, following the system setting by default.

NFR-UA-03 [M] The user should be able to access the symptom logging screen within 3 taps from the home screen, so that a symptom can be quickly logged when the user is feeling unwell.

NFR-UA-04 [M] Only portrait orientation shall be displayed in the app, to make it easy to navigate with one hand or while in bed with the phone held horizontally.