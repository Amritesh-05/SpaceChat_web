const state = {
  mode: "create",
  session: "",
  room: null,
  eventSource: null,
  typingTimer: null,
  openMessageMenuId: "",
  openMemberMenuName: "",
  localStream: null,
  peerConnections: new Map(),
  callOpen: false,
  replyTarget: null
};

const els = {
  authView: document.querySelector("#auth-view"),
  chatView: document.querySelector("#chat-view"),
  username: document.querySelector("#username"),
  roomName: document.querySelector("#room-name"),
  roomCode: document.querySelector("#room-code"),
  password: document.querySelector("#password"),
  maxUsers: document.querySelector("#max-users"),
  authError: document.querySelector("#auth-error"),
  submit: document.querySelector("#auth-submit"),
  roomNameWrap: document.querySelector("#room-name-wrap"),
  roomCodeWrap: document.querySelector("#room-code-wrap"),
  maxUsersWrap: document.querySelector("#max-users-wrap"),
  roomCodeLabel: document.querySelector("#room-code-label"),
  roomNameLabel: document.querySelector("#room-name-label"),
  topicLabel: document.querySelector("#topic-label"),
  memberCount: document.querySelector("#member-count"),
  members: document.querySelector("#members"),
  messages: document.querySelector("#messages"),
  typingLabel: document.querySelector("#typing-label"),
  messageInput: document.querySelector("#message-input"),
  composer: document.querySelector("#composer"),
  leaveBtn: document.querySelector("#leave-btn"),
  topicBtn: document.querySelector("#topic-btn"),
  copyCodeBtn: document.querySelector("#copy-code-btn"),
  inviteBtn: document.querySelector("#invite-btn"),
  callBtn: document.querySelector("#call-btn"),
  callPanel: document.querySelector("#call-panel"),
  endCallBtn: document.querySelector("#end-call-btn"),
  localVideo: document.querySelector("#local-video"),
  remoteVideos: document.querySelector("#remote-videos"),
  replyBanner: document.querySelector("#reply-banner"),
  replyBannerName: document.querySelector("#reply-banner-name"),
  replyBannerText: document.querySelector("#reply-banner-text"),
  clearReplyBtn: document.querySelector("#clear-reply-btn"),
  template: document.querySelector("#message-template")
};

document.querySelectorAll(".tab").forEach((button) => {
  button.addEventListener("click", () => setMode(button.dataset.mode));
});
els.submit.addEventListener("click", submitAuth);
els.composer.addEventListener("submit", sendMessage);
els.leaveBtn.addEventListener("click", leaveRoom);
els.topicBtn.addEventListener("click", updateTopic);
els.copyCodeBtn.addEventListener("click", copyRoomCode);
els.inviteBtn.addEventListener("click", inviteToRoom);
els.callBtn.addEventListener("click", toggleCall);
els.endCallBtn.addEventListener("click", endCall);
els.clearReplyBtn.addEventListener("click", clearReply);
els.messageInput.addEventListener("input", announceTyping);
setMode("create");

function setMode(mode) {
  state.mode = mode;
  document.querySelectorAll(".tab").forEach((button) => button.classList.toggle("active", button.dataset.mode === mode));
  els.roomNameWrap.classList.toggle("hidden", mode !== "create");
  els.maxUsersWrap.classList.toggle("hidden", mode !== "create");
  els.roomCodeWrap.classList.toggle("hidden", mode !== "join");
  els.authError.textContent = "";
}

async function submitAuth() {
  const payload = {
    username: els.username.value.trim(),
    roomName: els.roomName.value.trim(),
    code: els.roomCode.value.trim().toUpperCase(),
    password: els.password.value,
    maxUsers: Number(els.maxUsers.value || 30)
  };
  const path = state.mode === "create" ? "/api/rooms/create" : "/api/rooms/join";
  const result = await api(path, payload);
  if (!result.ok) {
    els.authError.textContent = result.error || "Could not enter room.";
    return;
  }
  state.session = result.session;
  hydrateRoom(result.room);
  connectEvents();
}

function hydrateRoom(room) {
  state.room = room;
  els.authView.classList.add("hidden");
  els.chatView.classList.remove("hidden");
  els.roomCodeLabel.textContent = getRoomCode(room);
  els.roomNameLabel.textContent = room.name;
  els.topicLabel.textContent = room.topic || "No topic set";
  els.topicBtn.style.display = room.admin === room.you ? "inline-flex" : "none";
  renderReplyBanner();
  renderMembers(room.members);
  renderMessages(room.messages);
  renderTyping(room.typing);
}

function connectEvents() {
  if (state.eventSource) {
    state.eventSource.close();
  }
  state.eventSource = new EventSource(`/api/rooms/${state.room.code}/events?session=${encodeURIComponent(state.session)}`);
  state.eventSource.onmessage = (event) => {
    const payload = JSON.parse(event.data);
    if (payload.type === "message.created") {
      state.room.messages.push(payload.message);
      renderMessages(state.room.messages);
      return;
    }
    if (payload.type === "message.updated") {
      state.room.messages = state.room.messages.map((message) => message.id === payload.message.id ? payload.message : message);
      renderMessages(state.room.messages);
      return;
    }
    if (payload.type === "message.deleted") {
      state.room.messages = state.room.messages.filter((message) => message.id !== payload.messageId);
      renderMessages(state.room.messages);
      return;
    }
    if (payload.type === "room.topic") {
      state.room.topic = payload.topic;
      els.topicLabel.textContent = payload.topic || "No topic set";
      return;
    }
    if (payload.type === "presence.updated" || payload.type === "typing.updated") {
      state.room.members = payload.members;
      state.room.typing = payload.typing;
      renderMembers(payload.members);
      renderTyping(payload.typing);
      return;
    }
    if (payload.type === "member.kicked") {
      if (payload.username === state.room.you) {
        alert("You were removed from the room.");
        location.reload();
      }
      return;
    }
    if (payload.type === "room.ended") {
      alert(payload.notice);
      location.reload();
      return;
    }
    if (payload.type === "call.signal") {
      handleSignal(payload);
    }
  };
}

function renderMembers(members) {
  els.memberCount.textContent = `${members.length} online`;
  els.members.innerHTML = "";
  members.forEach((member) => {
    const row = document.createElement("div");
    row.className = "member";
    row.innerHTML = `<div><strong>${escapeHtml(member.username)}</strong><small>${member.admin ? "admin" : member.muted ? "muted" : "member"}</small></div>`;
    if (state.room.admin === state.room.you && member.username !== state.room.you) {
      const actions = document.createElement("div");
      actions.className = "member-actions";
      const mute = document.createElement("button");
      mute.className = "ghost";
      mute.textContent = member.muted ? "Unmute" : "Mute";
      mute.onclick = () => api(`/api/rooms/${state.room.code}/${member.muted ? "unmute" : "mute"}`, { session: state.session, username: member.username });
      const kick = document.createElement("button");
      kick.className = "danger";
      kick.textContent = "Kick";
      kick.onclick = () => api(`/api/rooms/${state.room.code}/kick`, { session: state.session, username: member.username });
      actions.append(mute, kick);
      actions.classList.toggle("visible", state.openMemberMenuName === member.username);
      row.append(actions);
      row.addEventListener("dblclick", () => {
        state.openMessageMenuId = "";
        state.openMemberMenuName = state.openMemberMenuName === member.username ? "" : member.username;
        renderMembers(state.room.members);
      });
    }
    els.members.append(row);
  });
}

function renderMessages(messages) {
  els.messages.innerHTML = "";
  messages.forEach((message) => {
    const node = els.template.content.firstElementChild.cloneNode(true);
    const mine = message.sender === state.room.you;
    node.classList.toggle("mine", mine);
    node.querySelector(".avatar").textContent = message.sender.slice(0, 1).toUpperCase();
    node.querySelector(".sender").textContent = message.sender;
    node.querySelector(".time").textContent = new Date(message.timestamp).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }) + (message.edited ? " edited" : "");
    const bubble = node.querySelector(".bubble");
    if (message.replyTo && message.replyTo.id) {
      const reply = document.createElement("div");
      reply.className = "reply-snippet";
      reply.innerHTML = `<strong>${escapeHtml(message.replyTo.sender)}</strong><span>${escapeHtml(message.replyTo.text || "")}</span>`;
      bubble.append(reply);
    }
    bubble.append(document.createTextNode(message.text));
    node.querySelectorAll("[data-emoji]").forEach((button) => {
      button.onclick = () => api(`/api/rooms/${state.room.code}/reaction`, { session: state.session, messageId: message.id, emoji: button.dataset.emoji });
    });
    node.querySelector('[data-action="reply"]').onclick = () => {
      state.replyTarget = {
        id: message.id,
        sender: message.sender,
        text: message.text
      };
      renderReplyBanner();
      els.messageInput.focus();
    };
    node.querySelector('[data-action="edit"]').onclick = async () => {
      const nextText = prompt("Edit message", message.text);
      if (nextText !== null) {
        await api(`/api/rooms/${state.room.code}/edit`, { session: state.session, messageId: message.id, text: nextText });
      }
    };
    node.querySelector('[data-action="delete"]').onclick = () => api(`/api/rooms/${state.room.code}/delete`, { session: state.session, messageId: message.id });
    const actions = node.querySelector(".message-actions");
    actions.classList.toggle("visible", state.openMessageMenuId === message.id);
    node.addEventListener("dblclick", () => {
      state.openMemberMenuName = "";
      state.openMessageMenuId = state.openMessageMenuId === message.id ? "" : message.id;
      renderMessages(state.room.messages);
    });
    if (!mine) {
      node.querySelector('[data-action="edit"]').style.display = "none";
    }
    if (!mine && state.room.admin !== state.room.you) {
      node.querySelector('[data-action="delete"]').style.display = "none";
    }
    const reactions = node.querySelector(".reactions");
    Object.entries(message.reactions || {}).forEach(([emoji, users]) => {
      const pill = document.createElement("div");
      pill.className = "reaction";
      pill.textContent = `${emoji} ${users.length}`;
      reactions.append(pill);
    });
    els.messages.append(node);
  });
  els.messages.scrollTop = els.messages.scrollHeight;
}

function renderTyping(typingUsers) {
  const others = (typingUsers || []).filter((name) => name !== state.room.you);
  els.typingLabel.textContent = others.length ? `${others.join(", ")} typing...` : "";
}

async function sendMessage(event) {
  event.preventDefault();
  const text = els.messageInput.value.trim();
  if (!text) return;
  const result = await api(`/api/rooms/${state.room.code}/message`, {
    session: state.session,
    text,
    replyToId: state.replyTarget?.id || ""
  });
  if (result.ok) {
    els.messageInput.value = "";
    clearReply();
    announceTyping(false);
  }
}

function announceTyping(force = true) {
  clearTimeout(state.typingTimer);
  api(`/api/rooms/${state.room.code}/typing`, { session: state.session, active: force && !!els.messageInput.value.trim() });
  if (force && els.messageInput.value.trim()) {
    state.typingTimer = setTimeout(() => announceTyping(false), 2500);
  }
}

async function updateTopic() {
  const nextTopic = prompt("Set room topic", state.room.topic || "");
  if (nextTopic !== null) {
    await api(`/api/rooms/${state.room.code}/topic`, { session: state.session, topic: nextTopic });
  }
}

async function copyRoomCode() {
  const code = getRoomCode(state.room);
  try {
    await navigator.clipboard.writeText(code);
    alert(`Room code copied: ${code}`);
  } catch {
    alert(`Room code: ${code}`);
  }
}

async function inviteToRoom() {
  const code = getRoomCode(state.room);
  const inviteText = `Join my Space Chat room.\nRoom code: ${code}`;
  if (navigator.share) {
    try {
      await navigator.share({ title: "Space Chat Invite", text: inviteText });
      return;
    } catch {
      return;
    }
  }
  try {
    await navigator.clipboard.writeText(inviteText);
    alert("Invite copied to clipboard.");
  } catch {
    alert(inviteText);
  }
}

function renderReplyBanner() {
  const reply = state.replyTarget;
  els.replyBanner.classList.toggle("hidden", !reply);
  if (!reply) {
    return;
  }
  els.replyBannerName.textContent = `Replying to ${reply.sender}`;
  els.replyBannerText.textContent = reply.text;
}

function clearReply() {
  state.replyTarget = null;
  renderReplyBanner();
}

async function leaveRoom() {
  endCall(false);
  if (state.room.admin === state.room.you) {
    const end = confirm("You are the admin. End the room for everyone?");
    if (end) {
      await api(`/api/rooms/${state.room.code}/end`, { session: state.session });
      location.reload();
      return;
    }
  }
  await api(`/api/rooms/${state.room.code}/leave`, { session: state.session });
  location.reload();
}

async function toggleCall() {
  if (state.callOpen) {
    endCall();
    return;
  }
  await startLocalMedia();
  showCallPanel(true);
  await api(`/api/rooms/${state.room.code}/signal`, {
    session: state.session,
    target: "",
    signalType: "call.join",
    data: {}
  });
}

async function startLocalMedia() {
  if (state.localStream) {
    return state.localStream;
  }
  try {
    state.localStream = await navigator.mediaDevices.getUserMedia({ video: true, audio: true });
    els.localVideo.srcObject = state.localStream;
    return state.localStream;
  } catch (error) {
    alert("Camera or microphone access was blocked.");
    throw error;
  }
}

function showCallPanel(open) {
  state.callOpen = open;
  els.callPanel.classList.toggle("hidden", !open);
  els.callBtn.textContent = open ? "In Call" : "Video Call";
}

async function handleSignal(payload) {
  if (payload.from === state.room.you) {
    return;
  }
  if (payload.target && payload.target !== state.room.you) {
    return;
  }

  if (payload.signalType === "call.join") {
    if (!state.callOpen || !state.localStream) {
      return;
    }
    const peer = await ensurePeer(payload.from, true);
    const offer = await peer.createOffer();
    await peer.setLocalDescription(offer);
    await sendSignal(payload.from, "offer", offer);
    return;
  }

  if (payload.signalType === "offer") {
    await startLocalMedia();
    showCallPanel(true);
    const peer = await ensurePeer(payload.from, false);
    await peer.setRemoteDescription(new RTCSessionDescription(payload.data));
    const answer = await peer.createAnswer();
    await peer.setLocalDescription(answer);
    await sendSignal(payload.from, "answer", answer);
    return;
  }

  if (payload.signalType === "answer") {
    const peer = state.peerConnections.get(payload.from);
    if (peer) {
      await peer.setRemoteDescription(new RTCSessionDescription(payload.data));
    }
    return;
  }

  if (payload.signalType === "ice") {
    const peer = state.peerConnections.get(payload.from);
    if (peer && payload.data) {
      await peer.addIceCandidate(new RTCIceCandidate(payload.data)).catch(() => null);
    }
    return;
  }

  if (payload.signalType === "call.leave") {
    removePeer(payload.from);
  }
}

async function ensurePeer(username, createOfferSide) {
  let peer = state.peerConnections.get(username);
  if (peer) {
    return peer;
  }

  peer = new RTCPeerConnection({
    iceServers: [{ urls: "stun:stun.l.google.com:19302" }]
  });

  state.localStream.getTracks().forEach((track) => peer.addTrack(track, state.localStream));

  peer.onicecandidate = ({ candidate }) => {
    if (candidate) {
      sendSignal(username, "ice", candidate);
    }
  };

  peer.ontrack = (event) => {
    attachRemoteVideo(username, event.streams[0]);
  };

  peer.onconnectionstatechange = () => {
    if (["failed", "disconnected", "closed"].includes(peer.connectionState)) {
      removePeer(username);
    }
  };

  state.peerConnections.set(username, peer);

  if (createOfferSide) {
    return peer;
  }
  return peer;
}

async function sendSignal(target, signalType, data) {
  await api(`/api/rooms/${state.room.code}/signal`, {
    session: state.session,
    target,
    signalType,
    data
  });
}

function attachRemoteVideo(username, stream) {
  let tile = document.querySelector(`[data-remote="${username}"]`);
  if (!tile) {
    tile = document.createElement("div");
    tile.className = "video-tile";
    tile.dataset.remote = username;
    const video = document.createElement("video");
    video.autoplay = true;
    video.playsInline = true;
    const label = document.createElement("span");
    label.textContent = username;
    tile.append(video, label);
    els.remoteVideos.append(tile);
  }
  tile.querySelector("video").srcObject = stream;
}

function removePeer(username) {
  const peer = state.peerConnections.get(username);
  if (peer) {
    peer.onicecandidate = null;
    peer.ontrack = null;
    peer.close();
    state.peerConnections.delete(username);
  }
  document.querySelector(`[data-remote="${username}"]`)?.remove();
}

function endCall(notify = true) {
  if (notify && state.room && state.session) {
    api(`/api/rooms/${state.room.code}/signal`, {
      session: state.session,
      target: "",
      signalType: "call.leave",
      data: {}
    });
  }
  state.peerConnections.forEach((_, username) => removePeer(username));
  state.peerConnections.clear();
  if (state.localStream) {
    state.localStream.getTracks().forEach((track) => track.stop());
    state.localStream = null;
  }
  els.localVideo.srcObject = null;
  els.remoteVideos.innerHTML = "";
  showCallPanel(false);
}

async function api(url, body) {
  const response = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body)
  }).catch(() => null);
  if (!response) {
    return { ok: false, error: "Network error." };
  }
  return response.json();
}

function getRoomCode(room) {
  return room?.code || room?.roomCode || "------";
}

function escapeHtml(text) {
  return text.replace(/[&<>"']/g, (char) => ({
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    "\"": "&quot;",
    "'": "&#39;"
  }[char]));
}
