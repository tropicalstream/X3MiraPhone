# X3MiraPhone

The phone half of [X3Mira](https://github.com/tropicalstream/X3Mira): captures
this phone's screen with MediaProjection, encodes H.264 plus audio, and streams
it to RayNeo X3 Pro glasses over TCP — then injects the taps, scrolls and text
that come back from the temple pad.

It also performs the glasses' model calls on their behalf. The glasses have no
SIM, so when the pair falls back to Wi-Fi Direct they have no route to the
internet at all; every agent request is made from here, with the credential
attached at this end so a pair of glasses never carries a usable key.

| | |
|---|---|
| package | `com.x3mira.phone` |
| glasses app | [X3Mira](https://github.com/tropicalstream/X3Mira) (`com.x3mira.app`) |
| APKs | both are published in the X3Mira repo |

The two apps ship together — the wire format is shared and unversioned, so
update them as a pair.

---

## License

[MIT](LICENSE), with a project notice about what this half in particular can
do. It holds the **MediaProjection** grant, so it captures every pixel of this
phone's screen; it holds an **accessibility service**, so it can tap, scroll
and type as though someone were holding the device; and it performs the
glasses' **model calls**, so a picture of the screen leaves the phone when an
errand runs. The stream itself is not encrypted by this software.

Read it before granting either permission.
