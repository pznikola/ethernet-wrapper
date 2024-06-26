package ethernet

import chisel3._
import chisel3.stage.{ChiselGeneratorAnnotation, ChiselStage}
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.amba.axi4stream._
import freechips.rocketchip.diplomacy.AddressSet
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.bundlebridge.{BundleBridgeSink, BundleBridgeSource}
import org.chipsalliance.diplomacy.lazymodule._

class TLGbemacWrapper(csrAddress: AddressSet, beatBytes: Int) extends LazyModule()(Parameters.empty) {

  val configBlock: TLTemacConfig = LazyModule(new TLTemacConfig(csrAddress, beatBytes))

  // Nodes
  val mem: Option[TLRegisterNode] = configBlock.mem
  private val streamNode: AXI4StreamIdentityNode = AXI4StreamIdentityNode()
  val inNode = streamNode :=AXI4StreamWidthAdapter.oneToN(beatBytes)
  val outNode = AXI4StreamWidthAdapter.nToOne(beatBytes) := streamNode

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    // IOs
    val io: GbemacWrapperIO = IO(new GbemacWrapperIO)

    val gbemac: GbEMAC = Module(new GbEMAC())

    gbemac.io.clk                    := clock
    gbemac.io.clk125                 := io.clk125
    gbemac.io.clk125_90              := io.clk125_90
    gbemac.io.reset                  := reset

    io.phy_resetn                    := gbemac.io.phy_resetn
    io.rgmii_txd                     := gbemac.io.rgmii_txd
    io.rgmii_tx_ctl                  := gbemac.io.rgmii_tx_ctl
    io.rgmii_txc                     := gbemac.io.rgmii_txc
    gbemac.io.rgmii_rxd              := io.rgmii_rxd
    gbemac.io.rgmii_rx_ctl           := io.rgmii_rx_ctl
    gbemac.io.rgmii_rxc              := io.rgmii_rxc

    gbemac.io.tx_streaming_data      := streamNode.in.head._1.bits.data
    gbemac.io.tx_streaming_valid     := streamNode.in.head._1.valid
    gbemac.io.tx_streaming_last      := streamNode.in.head._1.bits.last
    streamNode.in.head._1.ready      := gbemac.io.tx_streaming_ready

    streamNode.out.head._1.bits.data := gbemac.io.rx_streaming_data
    streamNode.out.head._1.valid     := gbemac.io.rx_streaming_valid
    streamNode.out.head._1.bits.last := gbemac.io.rx_streaming_last
    gbemac.io.rx_streaming_ready     := streamNode.out.head._1.ready

    gbemac.io.packetSize             := configBlock.module.io.packetSize
    gbemac.io.srcMac                 := configBlock.module.io.srcMac
    gbemac.io.srcIp                  := configBlock.module.io.srcIp
    gbemac.io.srcPort                := configBlock.module.io.srcPort
    gbemac.io.dstMac                 := configBlock.module.io.dstMac
    gbemac.io.dstIp                  := configBlock.module.io.dstIp
    gbemac.io.dstPort                := configBlock.module.io.dstPort
    gbemac.io.dstPort2               := configBlock.module.io.dstPort2
    gbemac.io.dstPort1PacketNum      := configBlock.module.io.dstPort1PacketNum
    gbemac.io.dstPort2PacketNum      := configBlock.module.io.dstPort2PacketNum
  }
}

class TLGbemacWrapperBlock(csrAddress: AddressSet, beatBytes: Int)(implicit p: Parameters)
    extends TLGbemacWrapper(csrAddress, beatBytes) {
  val ioMem = mem.map { m => {
      val ioMemNode = BundleBridgeSource(() => TLBundle(standaloneParams))
      m := BundleBridgeToTL(TLMasterPortParameters.v1(Seq(TLMasterParameters.v1("bundleBridgeToTL")))) := ioMemNode
      val ioMem = InModuleBody { ioMemNode.makeIO() }
      ioMem
    }
  }
  val ioInNode = BundleBridgeSource(() => new AXI4StreamBundle(AXI4StreamBundleParameters(n = 4)))
  val ioOutNode = BundleBridgeSink[AXI4StreamBundle]()
  ioOutNode := AXI4StreamToBundleBridge(AXI4StreamSlaveParameters()) := outNode
  inNode := BundleBridgeToAXI4Stream(AXI4StreamMasterParameters(n = 4)) := ioInNode
  val in  = InModuleBody { ioInNode.makeIO() }
  val out = InModuleBody { ioOutNode.makeIO() }

  // Generate TL slave output
  def standaloneParams: TLBundleParameters =
    TLBundleParameters(
      addressBits    = beatBytes * 8,
      dataBits       = beatBytes * 8,
      sourceBits     = 1,
      sinkBits       = 1,
      sizeBits       = 6,
      echoFields     = Seq(),
      requestFields  = Seq(),
      responseFields = Seq(),
      hasBCE         = false
    )
}

object TLGbemacWrapperBlockApp extends App {
  implicit val p: Parameters = Parameters.empty
  val gbemacModule = LazyModule(new TLGbemacWrapperBlock(AddressSet(0x20000000, 0xff), 4))

  (new ChiselStage).execute(Array("--target-dir", "verilog/TLGbemacWrapper"), Seq(ChiselGeneratorAnnotation(() => gbemacModule.module)))
}
