**Mobile Development 2025/26 Portfolio**
# Requirements

Student ID: `C24053582`

**Requirements**

**General data management conventions**

Unless stated otherwise, any feature that stores user data will have the below behaviours:

* The user can tap on any saved entry to see its full details
* The user can edit all saved entries
* The user can delete all saved entries and must confirm the delete
* Should any entry be invalid, the app will display a message showing all invalid fields

These behaviours are not repeated in each feature below. Should any feature deviate from this pattern, an explicit explanation will be given.

**Functional requirements
Symptom logger**


FR-SL-01 The user shall be able to log their symptoms by logging a symptom its type from a predefined list or by entering a custom symptom when no predefined type is suitable

FR-SL-02 The user should be able to record the date and time of their symptom and have it pre-filled with the current date and time but be modifiable by the user.

FR-SL-03 The user should be able to log if the symptoms have ended or not and if they’ve ended record the end date and time of the symptom.
FR-SL-04 The user should be able to record the severity of a symptom from 1 to 10 using a slider with the number displayed clearly.

FR-SL-05 User should be able to record any medication they’re taking in free text field


FR-SL-06 User should be able to tag any extra context such from a predefined list such as stress, poor sleep, exercise and any additional notes that they want to add.

FR-SL-07 The app should allow the user to attach their device location to a symptom log entry, either manually or by using location services if the user has granted permission to do so.

FR-SL-08 A symptom log entry must contain a symptom type, description as well as a valid start date and time, to be saved. When saved with location the app should get the weather, air quality index, temperature, humidity and barometric pressure from the API.

FR-SL-09 Users should be able to view their symptoms, displayed in chronological order and be able to tap each symptom to show all stored details including environmental data.

FR-SL-10 If the user edits a symptom log entry, the original date, time and environmental data should be preserved and not re-fetched.

**AI-powered analysis**

FR-AA-01 The user should be able to access an AI analysis page where they can enter additional information and trigger AI powered analysis of the symptom data, additional provided information and the environmental data.

FR-AA-03 Each analysis run should show on an AI analysis page in chronological order with the result of the analysis and run timestamp displayed.

FR-AA-04 Upon successful analysis the result shall produce a guidance level on a new detailed screen such as monitor or seek a doctor’s advice with a list of identified correlations and contributing factors.

**PDF health report**

FR-RP-01 The user should be able to generate a PDF health report of the AI analysis that can be shown to their doctor.

FR-RP-02 The PDF report should include all records in chronological order with a summary section at the bottom displaying the total symptom entries and average symptom severity

FR-RP-03 The app should allow the user to preview the report in the app before export.

FR-RP-04 The app shall allow the user to share the PDF report to their app of choice.

FR-RP-05 The app should have a history of reports in chronological order with the export timestamp where the user can open share and delete a report.

**Notifications**

FR-NO-01 The user should be able to enable notifications for analysis results, requesting the result if needed. Should the user not want notifications anymore they should disable them in system settings.

FR-NO-02 When AI analysis is complete the app should send a notification saying if they’re in the clear or should seek medical advice and say that the result is available. Tapping the notification should open the result within the app.

**Non-functional requirements**

**Performance and responsiveness**

NFR-PE-01 Environmental data should be retrieved or time out within 5 seconds to ensure symptom logging stays responsive. Should it time out an error must be displayed.

NFR-PE-02 AI analysis should show as loading while waiting for a response and time out gracefully if a response is not received within 1 minute, notifying the user the analysis could not be completed.

NFR-PE-03 The app should launch and display the home screen within 3 seconds on a pixel 3a without any other application open.

NFR-PE-04 Symptom log entries should be saved within 1 second of the user submitting them, with a confirmation message once saved.

**Security and privacy**

NFR-SE-01 All user data must be encrypted and stored on device to keep the data secure.

NFR-SE-02 Device location data for environmental data retrieval must only be stored as an approximate by storing the coordinate rounded to two decimal places.

NFR-SE-03 All personally identifiable information should be removed before sending data to the AI API to be analysed, including names and date of birth. An approximate age range should be sent to the API instead for analysis.

**Reliability and resource management**

NFR-RE-01 All generated PDF reports stored on device should be compressed for storage efficiency

NFR-RE-02 Core functionality should still be available to the user when there is no network access including symptom logging and viewing past entries. Should the user request AI analysis without an internet connection they should be presented with a no network error.

NFR-RE-03 The app should have error handling for API response errors, notifying the user of the error without crashing.



NFR-RE-04 Location services should not be active in the background and should only be retrieved when a symptom is saved.

NFR-RE-05 Should there be no connection to the internet when fetching environmental data from the API, the app should notify the user that there is no internet connection without blocking the UI flow, so that the user can log a symptom without the environmental data. 


**Usability and accessibility**

NFR-UA-01 The app must follow Androids material design guidelines and have button sizes no smaller than 48x48dp for ease of use

NFR-UA-02 The app should work in both light and dark theme and follow the system setting by default. 

NFR-UA-03 Users should be able to get to the symptom logging screen within 3 taps from the home screen so that they can quickly log symptoms when feeling unwell.

