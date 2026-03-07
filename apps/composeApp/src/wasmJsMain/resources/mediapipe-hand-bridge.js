/**
 * MediaPipe Hand Tracking bridge for Kotlin/WASM.
 *
 * Handles async MediaPipe Vision SDK loading, getUserMedia camera capture,
 * and hand landmark detection. Results are stored as a flat Float32Array on
 * globalThis for efficient polling from Kotlin/WASM via js() interop.
 *
 * Result format (matches DesktopHandTracker JNI format):
 *   [numHands, hand0_handedness, hand0_x0,y0,z0,...x20,y20,z20, hand1_...]
 *   Per hand: 1 (handedness) + 21*3 (landmarks) = 64 floats
 *   handedness: 0 = Left, 1 = Right (from user's perspective in mirror view)
 *
 * Error reporting: bridge.lastError is set on failure and polled by Kotlin.
 */
(function () {
  var bridge = {
    landmarker: null,
    video: null,
    running: false,
    available: false,
    initFailed: false,
    lastError: '',
    frameSeq: 0,

    init: function () {
      var self = this;
      self.lastError = '';
      console.log('[MP] Loading MediaPipe Vision SDK from CDN...');
      return import(
        'https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@0.10.32/vision_bundle.mjs'
      ).then(function (vision) {
        console.log('[MP] SDK loaded, resolving WASM fileset...');
        return vision.FilesetResolver.forVisionTasks(
          'https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@0.10.32/wasm'
        ).then(function (fileset) {
          console.log('[MP] WASM fileset resolved, creating HandLandmarker...');
          return vision.HandLandmarker.createFromOptions(fileset, {
            baseOptions: {
              modelAssetPath:
                'https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task',
              delegate: 'GPU',
            },
            runningMode: 'VIDEO',
            numHands: 2,
          });
        });
      }).then(function (landmarker) {
        self.landmarker = landmarker;
        self.available = true;
        console.log('[MP] HandLandmarker ready');
      }).catch(function (e) {
        var msg = e && e.message ? e.message : String(e);
        self.lastError = 'MediaPipe init failed: ' + msg;
        self.initFailed = true;
        console.error('[MP] Init failed:', msg);
      });
    },

    startCamera: function () {
      if (this.running) {
        console.log('[MP] startCamera: already running');
        return Promise.resolve();
      }
      var self = this;
      self.lastError = '';
      console.log('[MP] startCamera called');

      if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
        self.lastError = 'getUserMedia not available (requires HTTPS or localhost)';
        console.error('[MP] ' + self.lastError);
        return Promise.reject(self.lastError);
      }

      var ready = this.landmarker
        ? Promise.resolve()
        : this.initFailed
          ? Promise.reject(this.lastError || 'MediaPipe init previously failed')
          : this.init();

      return ready.then(function () {
        if (!self.landmarker) {
          self.lastError = 'HandLandmarker not created (init may have failed)';
          console.warn('[MP] ' + self.lastError);
          return;
        }
        console.log('[MP] Requesting getUserMedia...');
        return navigator.mediaDevices
          .getUserMedia({
            video: { width: 640, height: 480, facingMode: 'user' },
          })
          .then(function (stream) {
            console.log('[MP] Camera stream obtained');
            self.video = document.createElement('video');
            self.video.srcObject = stream;
            self.video.playsInline = true;
            self.video.muted = true;
            return self.video.play();
          })
          .then(function () {
            self.running = true;
            self.lastError = '';
            console.log('[MP] Video playing, starting detection loop');
            self._detect();
          });
      }).catch(function (e) {
        var msg = e && e.message ? e.message : String(e);
        // Classify common errors
        if (msg.indexOf('NotAllowedError') >= 0 || msg.indexOf('Permission') >= 0) {
          self.lastError = 'Camera permission denied — allow camera access in browser settings';
        } else if (msg.indexOf('NotFoundError') >= 0 || msg.indexOf('no camera') >= 0) {
          self.lastError = 'No camera found on this device';
        } else if (msg.indexOf('NotReadableError') >= 0) {
          self.lastError = 'Camera is in use by another application';
        } else {
          self.lastError = 'Camera start failed: ' + msg;
        }
        console.error('[MP] ' + self.lastError);
      });
    },

    _detect: function () {
      if (!this.running || !this.video) return;
      var self = this;

      if (this.video.readyState >= 2) {
        try {
          var results = this.landmarker.detectForVideo(
            this.video,
            performance.now()
          );
          if (results.landmarks && results.landmarks.length > 0) {
            var numHands = results.landmarks.length;
            var arr = new Float32Array(1 + numHands * 64);
            arr[0] = numHands;

            for (var h = 0; h < numHands; h++) {
              var base = 1 + h * 64;
              // Invert handedness for mirror view:
              // Camera's "Right" = user's "Left"
              var hn = results.handedness[h] && results.handedness[h][0]
                ? results.handedness[h][0].categoryName
                : 'Right';
              arr[base] = hn === 'Right' ? 0.0 : 1.0; // 0=Left, 1=Right (user perspective)

              var lms = results.landmarks[h];
              for (var i = 0; i < 21 && i < lms.length; i++) {
                var off = base + 1 + i * 3;
                arr[off] = 1.0 - lms[i].x;     // Mirror X for natural mirror view
                arr[off + 1] = lms[i].y;
                arr[off + 2] = lms[i].z;
              }
            }

            globalThis.__mpHandResult = arr;
            globalThis.__mpHandFrameSeq = self.frameSeq++;
          } else {
            globalThis.__mpHandResult = null;
          }
        } catch (e) {
          // Detection errors during startup are normal (video not ready yet)
        }
      }

      requestAnimationFrame(function () { self._detect(); });
    },

    stopCamera: function () {
      console.log('[MP] stopCamera called');
      this.running = false;
      if (this.video && this.video.srcObject) {
        this.video.srcObject.getTracks().forEach(function (t) { t.stop(); });
        this.video.srcObject = null;
      }
      this.video = null;
      globalThis.__mpHandResult = null;
      globalThis.__mpHandFrameSeq = 0;
    },
  };

  globalThis.__mpHandBridge = bridge;
  console.log('[MP] Hand bridge registered on globalThis');
})();
