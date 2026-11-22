/* Crayfish Alarms: the script of the landing page. The page is readable without it. */
(function () {
  'use strict';

  // ---- theme ----

  var root = document.documentElement;
  var toggle = document.getElementById('theme-toggle');
  if (toggle) {
    toggle.hidden = false;
    toggle.addEventListener('click', function () {
      var dark = root.getAttribute('data-theme') === 'dark'
          || (!root.hasAttribute('data-theme') && window.matchMedia('(prefers-color-scheme: dark)').matches);
      var theme = dark ? 'light' : 'dark';
      root.setAttribute('data-theme', theme);
      try {
        localStorage.setItem('theme', theme);
      } catch (e) { /* no storage: the theme lasts until the page is left */ }
    });
  }

  // ---- highlighting ----

  var JAVA = [
    ['c', /\/\/[^\n]*/y],
    ['s', /"(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*'/y],
    ['n', /\b(?:0x[0-9A-Fa-f_]+|\d[\d_]*)L?\b/y],
    ['k', /\b(?:boolean|class|extends|false|final|if|implementation|implements|int|long|new|null|private|public|return|static|this|true|try|void|while)\b/y],
    ['t', /\b[A-Z][A-Za-z0-9_]*\b/y]
  ];
  var XML = [
    ['k', /<\/?[\w.-]+>/y]
  ];
  var RULES = { java: JAVA, groovy: JAVA, kotlin: JAVA, xml: XML };
  var VALUE = /\b\d+(?:[.,]\d+)?(?:–\d+(?:\.\d+)?)?\s?(?:ms|µs|s)\b/g;

  function span(kind, text) {
    var element = document.createElement('span');
    element.className = 'tok-' + kind;
    element.textContent = text;
    return element;
  }

  /** The comment as it is, but with the durations in it marked. */
  function comment(text) {
    var element = span('c', '');
    var last = 0;
    var match;
    VALUE.lastIndex = 0;
    while ((match = VALUE.exec(text)) !== null) {
      element.appendChild(document.createTextNode(text.slice(last, match.index)));
      element.appendChild(span('v', match[0]));
      last = match.index + match[0].length;
    }
    element.appendChild(document.createTextNode(text.slice(last)));
    return element;
  }

  function highlight(code) {
    var language = (/language-(\w+)/.exec(code.className) || [])[1];
    var rules = RULES[language];
    if (!rules) {
      return;
    }
    var text = code.textContent;
    var fragment = document.createDocumentFragment();
    var plain = '';
    var position = 0;
    while (position < text.length) {
      var found = null;
      for (var i = 0; i < rules.length && !found; i++) {
        var pattern = rules[i][1];
        pattern.lastIndex = position;
        var match = pattern.exec(text);
        if (match && match[0]) {
          found = [rules[i][0], match[0]];
        }
      }
      if (found) {
        if (plain) {
          fragment.appendChild(document.createTextNode(plain));
          plain = '';
        }
        fragment.appendChild(found[0] === 'c' ? comment(found[1]) : span(found[0], found[1]));
        position += found[1].length;
      } else {
        plain += text[position++];
      }
    }
    if (plain) {
      fragment.appendChild(document.createTextNode(plain));
    }
    code.textContent = '';
    code.appendChild(fragment);
  }

  function addCopy(pre) {
    if (!navigator.clipboard) {
      return;
    }
    var button = document.createElement('button');
    button.type = 'button';
    button.className = 'copy';
    button.textContent = 'Copy';
    button.addEventListener('click', function () {
      navigator.clipboard.writeText(pre.querySelector('code').textContent).then(function () {
        button.textContent = 'Copied';
        button.classList.add('done');
        setTimeout(function () {
          button.textContent = 'Copy';
          button.classList.remove('done');
        }, 1600);
      }, function () { /* no permission: nothing is copied */ });
    });
    pre.appendChild(button);
  }

  Array.prototype.forEach.call(document.querySelectorAll('pre > code'), function (code) {
    highlight(code);
    addCopy(code.parentNode);
  });

  // ---- tabs ----

  Array.prototype.forEach.call(document.querySelectorAll('[data-tabs]'), function (tabs) {
    var buttons = Array.prototype.slice.call(tabs.querySelectorAll('[role="tab"]'));

    function select(button, focus) {
      buttons.forEach(function (other) {
        var selected = other === button;
        other.setAttribute('aria-selected', String(selected));
        other.tabIndex = selected ? 0 : -1;
        document.getElementById(other.getAttribute('aria-controls')).hidden = !selected;
      });
      if (focus) {
        button.focus();
        if (button.scrollIntoView) {
          button.scrollIntoView({ block: 'nearest', inline: 'nearest' });
        }
      }
    }

    buttons.forEach(function (button, index) {
      button.addEventListener('click', function () {
        select(button, false);
      });
      button.addEventListener('keydown', function (event) {
        var target = { ArrowRight: index + 1, ArrowLeft: index - 1, Home: 0, End: buttons.length - 1 }[event.key];
        if (target !== undefined) {
          event.preventDefault();
          select(buttons[(target + buttons.length) % buttons.length], true);
        }
      });
    });
    select(buttons[0], false);
  });

  // ---- the simulation of a time range ----

  var sim = document.getElementById('sim');
  if (!sim) {
    return;
  }
  var RANGE = 10;
  var timeline = sim.querySelector('.timeline');
  var past = timeline.querySelector('.past');
  var playhead = timeline.querySelector('.playhead');
  var firedList = document.getElementById('sim-fired');
  var otherList = document.getElementById('sim-other');
  var clock = document.getElementById('sim-clock');
  var playButton = document.getElementById('sim-play');
  var speedButton = document.getElementById('sim-speed');
  var SPEEDS = [1, 2, 5];
  var now = 0;
  var playing = false;
  var speed = 0;
  var last = null;
  var nextId = 1;
  var lastAdded = null;
  var waiting = [];

  for (var second = 0; second <= RANGE; second++) {
    var chunk = document.createElement('div');
    chunk.className = 'chunk';
    chunk.style.left = (second / RANGE * 100) + '%';
    var label = document.createElement('span');
    label.textContent = String(second);
    chunk.appendChild(label);
    timeline.insertBefore(chunk, past);
  }

  function seconds(t) {
    return t.toFixed(2) + ' s';
  }

  function line(list, parts) {
    var empty = list.querySelector('.empty');
    if (empty) {
      list.removeChild(empty);
    }
    var item = document.createElement('li');
    parts.forEach(function (part) {
      var element = document.createElement('span');
      if (part[0]) {
        element.className = part[0];
      }
      element.textContent = part[1];
      item.appendChild(element);
    });
    list.appendChild(item);
    list.scrollTop = list.scrollHeight;
  }

  /** addElement of the range: an element out of the range is rejected, a duplicate of a waiting one is not added */
  function add(id, t) {
    if (t < 0 || t >= RANGE) {
      line(otherList, [['t', seconds(now) + '  '], ['k', 'rejected '], ['', id + ' '], ['v', 'due ' + seconds(t)]]);
      return;
    }
    var duplicate = waiting.some(function (alarm) {
      return alarm.id === id && alarm.t === t;
    });
    if (duplicate) {
      line(otherList, [['t', seconds(now) + '  '], ['k', 'duplicate '], ['', id + ' '], ['v', 'due ' + seconds(t)]]);
      return;
    }
    var dot = document.createElement('div');
    dot.className = 'dot';
    dot.style.left = (t / RANGE * 100) + '%';
    dot.style.top = (22 + (id.length * 37 + nextId * 53) % 56) + '%';
    dot.title = id + ', due ' + seconds(t);
    timeline.appendChild(dot);
    waiting.push({ id: id, t: t, dot: dot });
    lastAdded = { id: id, t: t };
  }

  /** extractFiredElements of the range: the due elements, in the order of their moments, then of their ids */
  function fire() {
    var due = waiting.filter(function (alarm) {
      return alarm.t <= now;
    });
    if (!due.length) {
      return;
    }
    waiting = waiting.filter(function (alarm) {
      return alarm.t > now;
    });
    due.sort(function (a, b) {
      return a.t - b.t || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0);
    });
    due.forEach(function (alarm) {
      line(firedList, [['t', seconds(now) + '  '], ['k', 'fired '], ['', alarm.id + ' '], ['v', 'due ' + seconds(alarm.t)]]);
      alarm.dot.classList.add('fired', 'flash');
      setTimeout(function () {
        alarm.dot.classList.remove('flash');
      }, 200);
    });
  }

  function render() {
    var share = (now / RANGE * 100) + '%';
    past.style.width = share;
    playhead.style.left = share;
    clock.innerHTML = '';
    var value = document.createElement('b');
    value.textContent = seconds(now);
    clock.appendChild(document.createTextNode('now '));
    clock.appendChild(value);
    clock.appendChild(document.createTextNode(' of ' + RANGE + ' s'));
  }

  function setPlaying(value) {
    playing = value && now < RANGE;
    playButton.textContent = playing ? 'Pause' : now >= RANGE ? 'Done' : 'Play';
    playButton.disabled = now >= RANGE;
    last = null;
    if (playing) {
      requestAnimationFrame(frame);
    }
  }

  function frame(time) {
    if (!playing) {
      return;
    }
    if (last !== null) {
      now = Math.min(RANGE, now + (time - last) / 1000 * SPEEDS[speed]);
    }
    last = time;
    fire();
    render();
    if (now >= RANGE) {
      setPlaying(false);
    } else {
      requestAnimationFrame(frame);
    }
  }

  function addRandom(count) {
    for (var i = 0; i < count; i++) {
      var from = Math.min(now + 0.3, RANGE - 0.01);
      add('A' + nextId++, Math.round((from + Math.random() * (RANGE - from)) * 100) / 100);
    }
  }

  function reset() {
    waiting.forEach(function (alarm) {
      timeline.removeChild(alarm.dot);
    });
    Array.prototype.forEach.call(timeline.querySelectorAll('.dot'), function (dot) {
      timeline.removeChild(dot);
    });
    waiting = [];
    now = 0;
    nextId = 1;
    lastAdded = null;
    [firedList, otherList].forEach(function (list) {
      list.innerHTML = '';
      var empty = document.createElement('li');
      empty.className = 'empty';
      empty.textContent = list.getAttribute('data-empty');
      list.appendChild(empty);
    });
    addRandom(8);
    render();
    setPlaying(false);
  }

  playButton.addEventListener('click', function () {
    setPlaying(!playing);
  });
  speedButton.addEventListener('click', function () {
    speed = (speed + 1) % SPEEDS.length;
    speedButton.textContent = '×' + SPEEDS[speed];
  });
  document.getElementById('sim-add').addEventListener('click', function () {
    addRandom(5);
  });
  document.getElementById('sim-duplicate').addEventListener('click', function () {
    if (lastAdded && waiting.some(function (alarm) { return alarm.id === lastAdded.id; })) {
      add(lastAdded.id, lastAdded.t);
    } else {
      addRandom(1);
      add(lastAdded.id, lastAdded.t);
    }
  });
  document.getElementById('sim-late').addEventListener('click', function () {
    add('A' + nextId++, Math.round((RANGE + 0.5 + Math.random() * 4) * 100) / 100);
  });
  document.getElementById('sim-reset').addEventListener('click', reset);
  timeline.addEventListener('click', function (event) {
    var box = timeline.getBoundingClientRect();
    var t = Math.round(Math.max(0, Math.min(0.9999, (event.clientX - box.left) / box.width)) * RANGE * 100) / 100;
    add('A' + nextId++, t);
    if (!playing) {
      fire();
    }
  });

  reset();
}());
