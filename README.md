# Space Chat

Space Chat is a browser-based real-time chat app built with plain Java on the backend and a custom HTML/CSS/JavaScript frontend. It started from a desktop chat concept and was reshaped into a web experience with live rooms, replies, reactions, admin controls, video calling, and a cinematic space-inspired interface.

## Features

- Real-time room-based chat
- Create and join rooms with room codes
- Message replies, reactions, edit, and delete
- Admin actions like mute, kick, and topic updates
- Browser-based video calling with WebRTC signaling
- Copy code and invite actions
- Space-themed UI with stars, constellations, moon, shooting stars, and horizon arc

## Tech Stack

- Java HTTP server
- HTML, CSS, and vanilla JavaScript
- Server-Sent Events for live updates
- WebRTC for video calls

## Run Locally

Use the included launcher:

```bat
run.bat
```

Then open:

```text
http://localhost:8080
```

You can also run it manually:

```bat
javac -d out src\Main.java src\server.java src\client.java src\client2.java
java -cp out Main
```

## Project Structure

- `src/server.java` - backend server and APIs
- `src/Main.java` - app entry point
- `web/index.html` - page structure
- `web/style.css` - custom styling and animated space background
- `web/app.js` - frontend behavior and live chat logic
- `run.bat` - quick local launcher

## Notes

- This project is designed to run locally without external frontend frameworks.
- Video calling depends on browser camera and microphone permissions.
