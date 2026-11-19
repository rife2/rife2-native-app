// Copyright 2026 Geert Bevin (gbevin[remove] at uwyn dot com)
// Licensed under the Apache License, Version 2.0 (the "License")

// Checks what RIFE2's script of the pages does in a web view of Apple and of
// Android, with the little of a page that it uses made up here.
//
//   node checks/script/check.mjs path/to/rife2-native-app.js
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const source = fs.readFileSync(process.argv[2], 'utf8');

// a page with the script's tag, in an app of a platform, with what the app says of itself
function page(platform, host, viewport = 'width=device-width', asked) {
    const sent = [];
    const classes = new Set();
    const meta = {content: viewport, setAttribute(name, value) { this[name] = value; }};
    const window = {
        document: {
            currentScript: {dataset: {platform: platform}},
            documentElement: {classList: {add: (...added) => added.forEach(each => classes.add(each))}},
            querySelector: selector => selector === 'meta[name=viewport]' ? meta : null
        },
        URL: URL,
        location: {href: 'https://site.invalid/', protocol: 'https:', host: 'site.invalid'},
        sent: sent,
        classes: classes,
        meta: meta
    };
    if (host !== undefined) {
        window.rife2NativeAppHost = host;
    }
    if (asked !== undefined) {
        window.Rife2NativeAppHost = {told: () => asked};
    }
    if (platform === 'android') {
        window.Rife2NativeApp = {addEventListener() {}, postMessage: said => sent.push(JSON.parse(said))};
    } else {
        // a message of Apple is a copy of its data, which a function can't be
        window.webkit = {messageHandlers: {rife2: {postMessage: said => {
            if (typeof said.data === 'function') {
                throw new Error('DataCloneError');
            }
            sent.push(said);
        }}}};
    }
    window.window = window;
    const context = vm.createContext(window);
    // what the script changes of Android's web view
    vm.runInContext('var HTMLAnchorElement = function() {}; HTMLAnchorElement.prototype.click = function() {};' +
        'var XMLHttpRequest = function() {}; XMLHttpRequest.prototype = {open() {}, send() {}, setRequestHeader() {}, abort() {}};' +
        'var HTMLFormElement = function() {}; HTMLFormElement.prototype.submit = function() {};' +
        'var navigator = {}; var URL = globalThis.URL; var addEventListener = function() {}; var history = {replaceState() {}}; var fetch = function() {};', context);
    vm.runInContext(source, context);
    return window;
}

// an app that tells a page what it hears
for (const platform of ['ios', 'android']) {
    const app = page(platform, {protocol: 1, heard: ['people'], zooms: false});
    const native = app.rife2NativeApp;
    assert.equal(native.platform, platform);
    assert.equal(native.protocol, 1);
    assert.equal(native.hears('people'), true);
    assert.equal(native.hears('elsewhere'), false);
    assert.equal(native.tell('people', {saved: 2}), true);
    assert.equal(native.tell('elsewhere', 'x'), false);
    assert.equal(native.tell('people'), true);
    assert.deepEqual(app.sent.map(said => JSON.parse(JSON.stringify(said))),
        [{action: 'tell', name: 'people', data: {saved: 2}}, {action: 'tell', name: 'people', data: null}]);

    // what the app tells the page
    const heard = [];
    assert.equal(native.told('chart', 1), false);
    native.on('chart', data => heard.push(data));
    assert.equal(native.told('chart', 2), true);
    native.on('chart', null);
    assert.equal(native.told('chart', 3), false);
    assert.equal(JSON.stringify(heard), '[2]');
    assert.ok(Object.isFrozen(native));
    assert.ok(app.classes.has('in-app') && app.classes.has('on-' + platform));
}

// what a message can't carry isn't told
assert.equal(page('ios', {protocol: 1, heard: ['people']}).rife2NativeApp.tell('people', () => 1), false);

// an app of before the protocol hears nothing of the page's own
const old = page('ios');
assert.equal(old.rife2NativeApp.protocol, 0);
assert.equal(old.rife2NativeApp.hears('people'), false);
assert.equal(old.rife2NativeApp.tell('people', 1), false);
assert.equal(old.sent.length, 0);

// an older web view of Android has the app say what it hears when it's asked
const older = page('android', undefined, 'width=device-width', JSON.stringify({protocol: 1, heard: ['people']}));
assert.equal(older.rife2NativeApp.protocol, 1);
assert.equal(older.rife2NativeApp.hears('people'), true);
assert.equal(page('android', undefined, 'width=device-width', 'not json').rife2NativeApp.hears('people'), false);
// what was added at the start of the page wins
assert.equal(page('android', {protocol: 1, heard: []}, 'width=device-width', JSON.stringify({protocol: 1, heard: ['people']})).rife2NativeApp.hears('people'), false);

// a page keeps its size unless the app says that it zooms
assert.match(page('ios', {protocol: 1, heard: []}).meta.content, /user-scalable=no/);
assert.equal(page('ios', {protocol: 1, heard: [], zooms: true}).meta.content, 'width=device-width');
assert.equal(page('android', {protocol: 1, heard: []}).meta.content, 'width=device-width');

// printing goes to the app
const printing = page('ios', {protocol: 1, heard: []});
printing.print();
assert.equal(JSON.stringify(printing.sent), '[{"action":"print"}]');

console.log('The script of the pages does what it should on Apple and Android');
