# Statement Checker

Statement Checker is an Android application designed to automate the process of requesting and handling M-Pesa statements. Built with modern Android development practices, it leverages Android Accessibility Services to navigate USSD menus autonomously, captures incoming statement SMS notifications, and can forward the resulting statements via email.

## 🚀 Key Features

*   **USSD Automation:** Utilizes Android Accessibility Services (`MpesaUssdService`) to automatically dial and navigate M-Pesa USSD menus without manual user intervention.
*   **SMS Listening:** Automatically detects and reads incoming SMS messages from M-Pesa using an SMS Broadcast Receiver (`SmsReceiver`).
*   **Email Forwarding:** Integrates the Java Mail API to automatically send or forward fetched statements to designated email addresses.
*   **Modern UI:** Built entirely with Jetpack Compose for a responsive, declarative, and smooth user interface.
*   **Background Processing:** Uses Android WorkManager for reliable background task execution.

## 🛠 Tech Stack

*   **Language:** Kotlin
*   **UI Framework:** Jetpack Compose, Material 3
*   **Architecture:** Android Architecture Components
*   **Key APIs & Libraries:**
    *   Android Accessibility Service API
    *   Broadcast Receivers (SMS)
    *   JavaMail API (`com.sun.mail`)
    *   WorkManager
    *   Navigation Compose

## 📋 Prerequisites

To build and run this project, you need:

*   **Android Studio:** Latest stable version recommended for full Jetpack Compose support.
*   **JDK:** Java 11 or higher.
*   **Android SDK:** Minimum SDK 24 (Android 7.0), Target SDK 36.
*   A physical Android device with an active SIM card (required for USSD and SMS functionality). Emulators generally do not support real USSD execution or receiving carrier SMS out of the box.

## ⚙️ Setup and Installation

1.  **Clone the Repository:**
    ```bash
    git clone https://github.com/David-mwas/statementchecker.git
    cd statementchecker
    ```
2.  **Open in Android Studio:**
    *   Open Android Studio and select **File > Open**.
    *   Navigate to the cloned `statementchecker` directory and select it.
3.  **Sync Gradle:**
    *   Wait for Android Studio to index the files and sync the Gradle project. If prompted, click **Sync Now**.
4.  **Build the Project:**
    *   Go to **Build > Make Project** (or press `Ctrl+F9` / `Cmd+F9`) to ensure everything compiles correctly.

## 📱 Running the App

1.  Connect your physical Android device to your computer via USB (ensure USB Debugging is enabled in Developer Options).
2.  Select your device from the run configurations dropdown menu in the Android Studio toolbar.
3.  Click the **Run** button (green play icon) or press `Shift+F10` / `Ctrl+R`.

### Required Permissions & Configuration

For the app to function correctly, the following permissions must be granted on your device:

*   **Accessibility Service:** You must manually enable the "Statement Checker" accessibility service in your device's **Settings > Accessibility**. This is crucial for the app to navigate the M-Pesa USSD menus.
*   **Phone & SMS Permissions:** The app requires permissions to Make/Manage Phone Calls (for USSD execution) and Read/Receive SMS (to capture incoming M-Pesa notifications).
*   **Notifications:** Required for alerting the user about statement extraction status.

## ⚠️ Important Guidelines & Security

*   **OEM Restrictions:** Manufacturers (like Huawei, Xiaomi, Samsung) have custom Android skins that can sometimes aggressively kill background services. Ensure battery optimization is **disabled** for this app to prevent the OS from terminating the USSD automation service.
*   **Sensitive Data:** This app handles sensitive financial data (M-Pesa statements). Ensure that any email credentials or routing configurations are kept secure, ideally managed via local properties or a secure backend, and never hardcoded in the public repository.
