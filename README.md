# 🎵 Rhythm Player App - Complete Project Specification

This repository contains the full architecture, UI flow, asset structure, and CI/CD configuration for building the Android Rhythm Player application using **Kotlin** and **Jetpack Compose**.

---

## 📱 1. UI Screens & Navigation Flow

All UI labels, menus, and navigation titles are strictly in **English**.

### 🔹 Screen 1: Main Categories (`Home`)
* **Top Bar:** App title ("Rhythm Player"), Search icon, Settings icon.
* **Category Grid:** Interactive cards for categories:
  * `Iranian`
  * `Kurdish`
  * `Turkish`
  * `Arabic`
  * `European`
  * `Favorites`
* **Bottom Navigation Bar:**
  * 🏠 `Home`
  * 📂 `Categories`
  * 🎵 `Library`
  * 🎧 `Player`

---

### 🔹 Screen 2: Sub-Menu / Time Signatures (`Time Signatures`)
* **Top Bar:**
  * ⬅️ **Back Button (`Back`):** Returns to the previous screen.
  * 🏠 **Home Button (`Home`):** Navigates directly back to the Home screen.
  * Category Title (e.g., "Kurdish Rhythms").
* **Foldered Rhythms Grid / List:**
  * `2/4` (Du Bash)
  * `3/4` (Gofend)
  * `4/4` (Chwar Bash)
  * `6/8` (Shash Bash)
  * `7/5` (Traditional)

---

### 🔹 Screen 3: Rhythm Track List (`Track List`)
* **Top Bar:**
  * ⬅️ `Back` button
  * 🏠 `Home` button
  * Time Signature Title (e.g., "Kurdish 4/4 Rhythms")
* **Track List (Min 20 items per category):**
  * Track Index & Title (e.g., "01. Govend 4/4 - Fast")
  * Base Tempo (BPM)
  * Duration
  * `Play / Pause` Button
  * Favorite Icon (⭐)

---

### 🔹 Screen 4: Loop Player & Controls (`Player`)
* **Navigation Header:** `Back` button and `Home` button.
* **Audio Controllers:**
  * 🎚️ **Tempo / Speed Slider:**
    * Precise step control (1 BPM increments).
    * Numeric Input field allowing direct value entry (e.g., 100, 120 BPM).
  * 🔊 **Volume Slider:** Adjust output master volume.
  * 🔁 **Seamless Audio Looping:** Zero-gap loop playback powered by Media3 ExoPlayer.

---

## 📁 2. Audio Assets Structure

All audio files (`.mp3` or `.wav`) are stored locally inside the project assets folder:

```text
app/src/main/assets/rhythms/
├── iranian/
│   ├── 2_4/
│   └── 4_4/
├── kurdish/
│   ├── 2_4/
│   ├── 3_4/
│   ├── 4_4/
│   ├── 6_8/
│   └── 7_5/
└── turkish/
```

---

## 🛠️ 3. Tech Stack & Architecture

* **Language:** Kotlin
* **UI Framework:** Jetpack Compose
* **Architecture Pattern:** MVVM (Model-View-ViewModel) + Clean Architecture
* **Audio Engine:** AndroidX Media3 / ExoPlayer (Optimized for seamless audio loops)
* **Navigation:** Jetpack Navigation Compose

---

## 🚀 4. GitHub Actions CI/CD Workflow

File path: `.github/workflows/build.yml`

Automates building a debug APK on every commit to `main`.
