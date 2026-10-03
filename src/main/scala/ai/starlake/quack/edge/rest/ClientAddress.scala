package ai.starlake.quack.edge.rest

import ai.starlake.quack.ondemand.fleet.Cidr

import java.net.{Inet6Address, InetAddress}

/** The client key the REST data edge's failed-authentication throttle buckets requests by: an
  * HTTP-layer resource control, never an identity or a grant.
  *
  * The key is the TCP peer. `X-Forwarded-For` is read only when that peer is inside
  * `trustedProxies`: it is then walked from the RIGHT, since every trusted proxy appends the
  * address it saw, and the first hop that is not itself a trusted proxy is the client; anything
  * left of it is whatever the client chose to send, and is never read. When every hop is trusted
  * the left-most one is taken. A port a proxy appended (`a.b.c.d:port`, `[v6]:port`) is dropped. A
  * header that is empty, or holds on the way a hop that is not an address (`unknown`, a host name,
  * anything malformed), falls back to the peer: that hop is never the client, and what lies left of
  * it is untrusted. A client outside the trusted set therefore can never choose its key.
  *
  * Without `trustedProxies`, a load balancer in front of the edge is the peer of every request, so
  * all its clients share one key: one client's bad tokens then block everyone behind it.
  *
  * This departs from the fleet heartbeat's [[ai.starlake.quack.ondemand.fleet.ClientAddress]] on
  * purpose: there the address GRANTS approval, so "unknown" must never match; here it only buckets
  * failures, so the peer (the proxy) is the safe fallback, and it is only reached through a header
  * that the trusted proxy itself wrote badly.
  *
  * Normalisation: an IPv4-mapped IPv6 address is its IPv4 address, and native IPv6 is keyed per /64
  * (a single subscriber usually holds a whole /64, so a per-address key would be free to rotate).
  */
object ClientAddress:

  /** The key of a request with no peer at all (only reachable off the wire, e.g. in a spec). */
  val Unknown = "unknown"

  /** The throttle key of one request. */
  def keyOf(peer: Option[InetAddress], forwardedFor: List[String], trusted: List[Cidr]): String =
    peer.map(normalise).fold(Unknown)(p => key(resolve(p, forwardedFor, trusted)))

  /** The key of one address: dotted IPv4, or the /64 of an IPv6 address as `a:b:c:d::/64`. */
  def key(addr: InetAddress): String =
    normalise(addr) match
      case v6: Inet6Address =>
        val b = v6.getAddress
        (0 until 4)
          .map(i => Integer.toHexString(((b(2 * i) & 0xff) << 8) | (b(2 * i + 1) & 0xff)))
          .mkString("", ":", "::/64")
      case v4 => v4.getHostAddress

  private def resolve(peer: InetAddress, forwardedFor: List[String], trusted: List[Cidr]) =
    if !Cidr.anyContains(trusted, peer) then peer
    else
      val hops = forwardedFor.flatMap(_.split(",", -1).toList).map(_.trim)
      if hops.forall(_.isEmpty) then peer
      else walk(hops.reverse, trusted).getOrElse(peer)

  /** Right to left: the first untrusted hop, else the left-most (all trusted); None as soon as a
    * hop on the way does not parse.
    */
  private def walk(fromRight: List[String], trusted: List[Cidr]): Option[InetAddress] =
    fromRight match
      case Nil       => None
      case h :: rest =>
        address(h).map(normalise) match
          case None                                     => None
          case Some(a) if !Cidr.anyContains(trusted, a) => Some(a)
          case Some(a) if rest.isEmpty                  => Some(a)
          case Some(_)                                  => walk(rest, trusted)

  private val V4WithPort = """(\d{1,3}(?:\.\d{1,3}){3}):(\d{1,5})""".r
  private val V6WithPort = """\[([^\]]+)\](?::(\d{1,5}))?""".r

  /** One hop as an address, its port dropped; None for anything that is not an IP literal. */
  private def address(hop: String): Option[InetAddress] =
    def port(p: String): Boolean = p == null || p.toInt <= 65535
    hop match
      case V4WithPort(a, p) => Option.when(port(p))(a).flatMap(Cidr.parseAddress)
      case V6WithPort(a, p) => Option.when(port(p))(a).flatMap(Cidr.parseAddress)
      case other            => Cidr.parseAddress(other)

  /** `::ffff:a.b.c.d` as `a.b.c.d`. The JDK already does this for a parsed literal; a socket peer
    * built from raw bytes may still arrive as an `Inet6Address`.
    */
  private def normalise(addr: InetAddress): InetAddress =
    addr match
      case v6: Inet6Address =>
        val b      = v6.getAddress
        val mapped = (0 until 10).forall(b(_) == 0) && b(10) == -1 && b(11) == -1
        if mapped then InetAddress.getByAddress(b.slice(12, 16)) else v6
      case other => other
