// Gives the tester a deterministic window to stop/pause the backend before
// Maestro taps the submit button for offline replay validation.
//
// Maestro runs scripts in GraalJS, not a browser/Node event loop, so setTimeout
// is not available here. Use a small synchronous wait instead.
var startedAt = Date.now()
while (Date.now() - startedAt < 60000) {
  // busy wait
}
output.done = true
