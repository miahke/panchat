package main

import (
	"net"
	"time"
)

// Read errors, including silence deadlines, end this worker. Socket writes
// alone never prove that an authenticated peer is still reachable.
func readPeerPacket(c net.Conn, b []byte, timeout time.Duration) (int, error) {
	if err := c.SetReadDeadline(time.Now().Add(timeout)); err != nil {
		return 0, err
	}
	return c.Read(b)
}
