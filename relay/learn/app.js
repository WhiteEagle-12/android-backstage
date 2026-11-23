(function () {
  'use strict';
  var root = document.getElementById('activity');
  var context = TeachmeRunner.parseActivityLink(location.hash);
  var storageKey = 'teachme-result:' + (context.activityId || 'unlabelled') + ':' + context.version, pending = [], sending = false, waitingQuestion = '', idleTimer = null, sequence = 0, startedAt = Date.now(), activity = null, questionInput;
  var MAX_EVENTS = 120, MAX_BYTES = 60 * 1024;
  function safeText(value, max) { return String(value == null ? '' : value).slice(0, max || 240); }
  function status(text, kind) { var node = document.getElementById('send-status'); if (!node) return; node.textContent = text; node.className = 'send-status ' + (kind || ''); }
  function boundedEvent(event) {
    if (pending.length >= MAX_EVENTS) return;
    var clean = { type: safeText(event.type, 30), atMs: Math.max(0, Date.now() - startedAt), order: ++sequence };
    ['itemId', 'choice', 'part', 'result', 'message'].forEach(function (key) { if (event[key] != null) clean[key] = safeText(event[key], key === 'message' ? 240 : 100); });
    ['score', 'total'].forEach(function (key) { if (Number.isFinite(event[key])) clean[key] = event[key]; });
    if (Array.isArray(event.answers)) clean.answers = event.answers.slice(0, 40).map(function (answer) { return { itemId: safeText(answer.itemId, 100), choice: safeText(answer.choice, 100), correct: Boolean(answer.correct) }; });
    pending.push(clean); savePending(); if (clean.type === 'explore') { clearTimeout(idleTimer); idleTimer = setTimeout(function () { sendPending(); }, 1500); }
  }
  function savePending() { try { localStorage.setItem(storageKey, JSON.stringify({ pending: pending.slice(-MAX_EVENTS), question: waitingQuestion })); } catch (error) {} }
  function loadPending() { try { var saved = JSON.parse(localStorage.getItem(storageKey) || '{}'); if (saved && Array.isArray(saved.pending)) pending = saved.pending.slice(-MAX_EVENTS); waitingQuestion = typeof saved.question === 'string' ? saved.question.slice(0, 500) : ''; } catch (error) {} }
  function apiEvent(event) { var type = event.type === 'choice' ? 'selection' : event.type === 'check' ? 'attempt' : event.type === 'hint' ? 'feedback' : event.type; var out = { type: type, atMs: event.atMs }; if (event.itemId) out.questionId = safeText(event.itemId, 100); if (event.type === 'explore') { delete out.questionId; if (event.part === 'chart-point') out.pointId = safeText(event.itemId, 100); else if (event.part === 'model-face') out.faceId = safeText(event.itemId, 100); else out.nodeId = safeText(event.itemId, 100); } if (event.choice != null) out.value = safeText(event.choice, 100); if (event.message) { out.feedbackId = event.result || 'hint'; out.text = safeText(event.message, 500); } return out; }
  function payload(events, question) { var answers = [], score; events.forEach(function (event) { if (event.type === 'check' && Array.isArray(event.answers)) { answers = event.answers.map(function (answer) { return { questionId: safeText(answer.itemId, 100), value: safeText(answer.choice, 100), correct: Boolean(answer.correct) }; }); score = { earned: event.score || 0, total: event.total || answers.length }; } }); var body = { answers: answers, events: events.filter(function (event) { return ['choice','check','hint','feedback','explore'].indexOf(event.type) >= 0; }).map(apiEvent) }; if (score) body.score = score; if (question) { body.learnerQuestions = [{ text: safeText(question, 500) }]; body.events.push({ type: 'question', text: safeText(question, 500) }); } return body; }
  async function sendPending(question) {
    if (question) waitingQuestion = safeText(question, 500);
    savePending();
    if (sending) return;
    var activeQuestion = question || waitingQuestion;
    if ((!pending.length && !activeQuestion) || !context.writeToken) return;
    sending = true; var events = pending.slice(0, MAX_EVENTS), body = payload(events, activeQuestion);
    while (JSON.stringify(body).length > MAX_BYTES && events.length > 1) { events = events.slice(0, Math.ceil(events.length / 2)); body = payload(events, activeQuestion); }
    var succeeded = false;
    try { var response = await fetch('/v1/learn/activities/' + encodeURIComponent(context.activityId) + '/results', { method: 'POST', headers: { 'content-type': 'application/json', 'authorization': 'Bearer ' + context.writeToken }, body: JSON.stringify(body), keepalive: true }); if (!response.ok) throw new Error('send failed'); succeeded = true; pending.splice(0, events.length); if (!waitingQuestion || waitingQuestion === activeQuestion) waitingQuestion = ''; savePending(); status('Saved', 'ok'); if (activeQuestion && (!waitingQuestion || waitingQuestion === activeQuestion)) questionInput.value = ''; }
    catch (error) { status('Not saved. Tap Retry when you are online.', 'error'); }
    finally { sending = false; if (succeeded && (waitingQuestion || pending.length)) setTimeout(function () { sendPending(); }, 0); }
  }
  function addQuestionBox() {
    if (!context.writeToken) return;
    var box = document.createElement('section'); box.className = 'question-box';
    var label = document.createElement('label'); label.setAttribute('for', 'learner-question'); label.textContent = 'Ask a question';
    questionInput = document.createElement('textarea'); questionInput.id = 'learner-question'; questionInput.rows = 3; questionInput.maxLength = 1000; questionInput.placeholder = 'What should I explain?';
    var send = document.createElement('button'); send.type = 'button'; send.className = 'primary'; send.textContent = 'Send question';
    var retry = document.createElement('button'); retry.type = 'button'; retry.textContent = 'Retry'; retry.onclick = function () { sendPending(); };
    var line = document.createElement('div'); line.className = 'question-actions'; line.append(send, retry);
    var note = document.createElement('p'); note.id = 'send-status'; note.className = 'send-status'; note.textContent = 'Your answers will be sent with this activity.';
    send.onclick = function () { var text = questionInput.value.trim(); if (text) sendPending(text); };
    box.append(label, questionInput, line, note); root.appendChild(box);
  }
  function itemIdForCard(card) { if (card.dataset.itemId) return card.dataset.itemId; var heading = card.querySelector('h2'); var text = heading ? heading.textContent : ''; if (activity.type === 'quiz') { var question = activity.questions.find(function (entry, index) { return text === (index + 1) + '. ' + entry.prompt; }); if (question) return question.id; } return text; }
  function recordExploreClicks() {
    if (activity.type === 'diagram') Array.from(root.querySelectorAll('.diagram-node')).forEach(function (node, index) { if (activity.nodes[index]) node.dataset.itemId = activity.nodes[index].id; });
    if (activity.type === 'chart') { var pointIndex = 0; activity.series.forEach(function (series) { series.values.forEach(function (value, valueIndex) { var point = root.querySelectorAll('.chart-point')[pointIndex++]; if (point) point.dataset.itemId = series.label + ':' + ((activity.labels && activity.labels[valueIndex]) || 'point ' + (valueIndex + 1)); }); }); }
    root.addEventListener('click', function (event) { var target = event.target; if (!target || !activity) return;
      if (target.classList.contains('choice')) { var card = target.closest('.item'); if (card) boundedEvent({ type: 'choice', itemId: itemIdForCard(card), choice: target.textContent }); }
      else if (target.classList.contains('primary') && target.textContent === 'Check answers') { var answers = Array.from(root.querySelectorAll('.item')).map(function (card) { var selected = card.querySelector('.choice.selected'), itemId = itemIdForCard(card), choice = selected ? selected.textContent : '', correct = false; if (activity.type === 'classify') { var item = activity.items.find(function (entry) { return entry.id === itemId; }), category = item && activity.categories.find(function (entry) { return entry.id === item.category; }); correct = Boolean(category && category.label === choice); } else if (activity.type === 'quiz') { var question = activity.questions.find(function (entry) { return entry.id === itemId; }), index = selected ? Array.from(card.querySelectorAll('.choice')).indexOf(selected) : -1; correct = Boolean(question && index === question.answer); } return { itemId: itemId, choice: choice, correct: correct }; }); boundedEvent({ type: 'check', answers: answers, score: answers.filter(function (answer) { return answer.correct; }).length, total: answers.length }); setTimeout(function () { Array.from(root.querySelectorAll('.feedback')).forEach(function (node) { if (node.textContent && !node.dataset.logged) { node.dataset.logged = '1'; boundedEvent({ type: 'feedback', message: node.textContent, result: node.classList.contains('success') ? 'correct' : 'review' }); } }); sendPending(); }, 50); }
      else if (target.closest && target.closest('.diagram-node')) { var diagramNode = target.closest('.diagram-node'); boundedEvent({ type: 'explore', itemId: diagramNode.dataset.itemId || diagramNode.getAttribute('aria-label'), part: 'diagram-node' }); }
      else if (target.classList.contains('diagram-target')) { var node = activity.nodes && activity.nodes.find(function (n) { return n.label === target.textContent; }); if (node) boundedEvent({ type: 'explore', itemId: node.id, part: 'diagram-target' }); }
      else if (target.closest && target.closest('.chart-point')) { var chartPoint = target.closest('.chart-point'); boundedEvent({ type: 'explore', itemId: chartPoint.dataset.itemId || chartPoint.getAttribute('aria-label'), part: 'chart-point' }); }
      else if (target.classList.contains('chart-target')) boundedEvent({ type: 'explore', itemId: safeText(target.textContent, 100), part: 'chart-point' });
      else if (target.closest && target.closest('.model-face')) { var modelFace = target.closest('.model-face'); boundedEvent({ type: 'explore', itemId: Array.from(modelFace.classList).find(function (x) { return ['front','right','back','left','top','bottom'].indexOf(x) >= 0; }) || modelFace.textContent, part: 'model-face' }); }
    });
  }
  async function start() { try { loadPending(); activity = await TeachmeRunner.parseActivityHash(location.hash); TeachmeRunner.mount(activity, root, { onEvent: boundedEvent }); recordExploreClicks(); addQuestionBox(); if (!context.writeToken) status('Practice only', ''); else if (pending.length || waitingQuestion) sendPending(); } catch (error) { root.textContent = ''; var title = document.createElement('h1'); title.textContent = 'Activity unavailable'; var message = document.createElement('p'); message.textContent = error.message; root.append(title, message); } }
  window.addEventListener('online', function () { if (pending.length) sendPending(); });
  start();
}());
