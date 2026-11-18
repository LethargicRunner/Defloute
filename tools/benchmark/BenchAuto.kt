import com.lethargicrunner.defloute.*
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

fun main(args: Array<String>) {
    val cfg = args[0]; val inDir = File(args[1]); val outDir = File(args[2]); outDir.mkdirs()
    val t0 = System.currentTimeMillis()
    val files = inDir.listFiles { f -> f.name.endsWith(".png") }!!.sortedBy { it.name }
    val diagOut = StringBuilder()
    var tune = AutoRestore.Tuning()
    for (a in args.drop(3)) {
        val (k, v) = a.split("=")
        tune = when (k) {
            "nlmK" -> tune.copy(nlmSigmaK = v.toFloat()); "nlmS" -> tune.copy(nlmStrength = v.toFloat())
            "tone" -> tune.copy(toneTarget = v.toFloat()); "nsrB" -> tune.copy(nsrBase = v.toFloat()); "nsrN" -> tune.copy(nsrNoise = v.toFloat())
            "shrink" -> tune.copy(kernelShrink = v.toFloat()); "tlow" -> tune.copy(toneLow = v.toFloat()); "thigh" -> tune.copy(toneHigh = v.toFloat()); "tstr" -> tune.copy(toneStrength = v.toFloat()); "stretch" -> tune.copy(stretchBelow = v.toFloat()); else -> tune
        }
    }
    for (f in files) {
        val img = ImageIO.read(f); val w = img.width; val h = img.height
        val px = IntArray(w * h) { img.getRGB(it % w, it / w) or (0xFF shl 24) }
        val d = PhotoDiagnosis.analyse(px, w, h)
        val res = if (cfg == "diag") null else AutoRestore.run(px, w, h, d, tune)
        diagOut.append("${f.name};${d.blurType};${"%.2f".format(d.motionLength)};${"%.1f".format(d.motionAngleDeg)};${"%.2f".format(d.isoSigma)};${"%.2f".format(d.noiseSigma)};${"%.3f".format(d.blockiness)};${d.medianLuma};${d.p1};${d.p99}\n")
        if (res != null) {
            val o = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            for (i in res.pixels.indices) o.setRGB(i % w, i / w, res.pixels[i] and 0xFFFFFF)
            ImageIO.write(o, "png", File(outDir, f.name))
        }
    }
    File(outDir.parentFile, "diag_${outDir.name}.csv").writeText(diagOut.toString())
    println("$cfg : ${files.size} images en ${(System.currentTimeMillis() - t0) / 1000} s")
}
