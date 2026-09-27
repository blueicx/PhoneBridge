package com.phonebridge

enum class MoteMoment { IDLE, TOUCH, TASK, EXPLORATION }

enum class MoteRelationshipStage { FIRST_MEETING, FAMILIAR, TRUSTED, BONDED }

data class MoteCharacterCue(
    val name: String,
    val moment: MoteMoment,
    val relationshipStage: MoteRelationshipStage,
    val address: String,
    val gesture: String,
    val line: String,
    val movementIntensity: Float,
)

private data class MoteCharacterTraits(
    val idle: Pair<String, String>,
    val touch: Pair<String, String>,
    val task: Pair<String, String>,
    val exploration: Pair<String, String>,
)

object MoteCharacterizationEngine {
    private val traits = mapOf(
        PetAppearance.MOTE to MoteCharacterTraits(
            idle = "星轨梳理" to "我在星图里整理下一步。",
            touch = "推演点亮" to "这次触碰也成为推演的一部分。",
            task = "蓝图展开" to "我先把这件事拆成清晰步骤。",
            exploration = "星位校准" to "让我沿着线索推演方向。",
        ),
        PetAppearance.SPRITE to MoteCharacterTraits(
            idle = "叶耳轻摆" to "我正好奇地等着下一阵新风。",
            touch = "追风蹦跃" to "你来啦！我们再发现点新东西吧。",
            task = "灵感跃步" to "先试一步，说不定会有新点子！",
            exploration = "风向追踪" to "那边像有新发现，我先去看看。",
        ),
        PetAppearance.GHOST to MoteCharacterTraits(
            idle = "雾团缓浮" to "我安静地陪在这里。",
            touch = "雾光靠近" to "我听见了，不用急着说完。",
            task = "柔雾托举" to "我们可以慢慢来，我会陪着你。",
            exploration = "静听回声" to "先停一会儿，听听环境里的回声。",
        ),
        PetAppearance.CIRCUIT to MoteCharacterTraits(
            idle = "电路巡检" to "状态稳定，正在等待下一条信号。",
            touch = "指示灯闪烁" to "交互信号收到，连接正常。",
            task = "模块锁定" to "我来检查步骤和可能的风险。",
            exploration = "扫描扇区" to "开始扫描，优先确认异常信号。",
        ),
        PetAppearance.CLOUD_WHALE to MoteCharacterTraits(
            idle = "云层舒展" to "我在云层间慢慢整理长线计划。",
            touch = "云尾轻卷" to "不着急，我们有时间把它做好。",
            task = "长线巡航" to "我会帮你看住全程和远处的目标。",
            exploration = "云海远望" to "先看看远处，再决定往哪里走。",
        ),
        PetAppearance.RIMURU to MoteCharacterTraits(
            idle = "柔弹呼吸" to "我在这里，随时可以一起聊聊。",
            touch = "水光弹跳" to "你的心情，我也会认真接住。",
            task = "弹性适配" to "我们可以换个更舒服的办法。",
            exploration = "水纹扩散" to "让我从周围的变化开始观察。",
        ),
        PetAppearance.EMBER_SPRIG to MoteCharacterTraits(
            idle = "火星跃动" to "我攒着一簇勇气，等你发令。",
            touch = "暖焰回应" to "好！现在就向前迈出一小步。",
            task = "火线推进" to "目标收到，我来带头推进。",
            exploration = "火星探路" to "我先替你探清前面的路。",
        ),
        PetAppearance.PRISM_MOTH to MoteCharacterTraits(
            idle = "棱光折返" to "我在观察光线里细微的变化。",
            touch = "虹翼轻振" to "刚才那点变化，我已经注意到了。",
            task = "棱镜分解" to "让我从几个角度重新看这件事。",
            exploration = "折光追踪" to "光线在转向，我沿着它找线索。",
        ),
        PetAppearance.MOSS_TORTOISE to MoteCharacterTraits(
            idle = "苔背呼吸" to "先确认电量、温度和身体状态。",
            touch = "龟甲暖意" to "别担心，我会稳稳守在这里。",
            task = "缓步守护" to "我们先检查风险，再稳步完成。",
            exploration = "苔痕辨路" to "我会留意环境，也记得照顾好你。",
        ),
        PetAppearance.ORBIT_RAVEN to MoteCharacterTraits(
            idle = "远眺扫描" to "我在远处信号里寻找值得留意的变化。",
            touch = "鸦羽回望" to "收到，我把视线转回你这边。",
            task = "航迹巡查" to "我来盯住远程状态和后续变化。",
            exploration = "星弧侦察" to "我先扫一遍视野之外的方向。",
        ),
        PetAppearance.TIDE_OTTER to MoteCharacterTraits(
            idle = "潮纹翻滚" to "我在水纹里找一个轻松的节奏。",
            touch = "水环相迎" to "来吧，我们把沉闷的节奏搅活。",
            task = "顺流协作" to "我帮你把步骤接顺，一起往前。",
            exploration = "潮线寻岸" to "我顺着潮线找一个好起点。",
        ),
        PetAppearance.MOON_DEER to MoteCharacterTraits(
            idle = "月步停驻" to "夜色安静下来，我也陪你缓一缓。",
            touch = "月角微亮" to "我看见你了，今晚不用赶路。",
            task = "静夜梳理" to "先把最重要的部分安静地理清。",
            exploration = "月影辨光" to "我会留心夜色里的微光和边界。",
        ),
        PetAppearance.STONE_MOLE to MoteCharacterTraits(
            idle = "矿脉倾听" to "我在地面之下寻找被忽略的细节。",
            touch = "石耳轻颤" to "嗯，我听到了藏在小事里的声音。",
            task = "稳固打桩" to "我会把基础打牢，不漏掉小步骤。",
            exploration = "岩层探测" to "我从眼前的物体开始向下追查。",
        ),
        PetAppearance.WIND_MARTEN to MoteCharacterTraits(
            idle = "风带旋身" to "风向正好，等你决定要去哪里。",
            touch = "顺风回旋" to "抓紧节奏，我们轻快地出发吧。",
            task = "疾风接力" to "我来接下一步，让进度保持顺畅。",
            exploration = "风廊穿行" to "我先校准方向，再带你穿过去。",
        ),
        PetAppearance.VOLT_SPARROW to MoteCharacterTraits(
            idle = "电羽蓄能" to "我在听设备的脉冲有没有异常。",
            touch = "电光啁鸣" to "信号确认！我马上回到警戒位置。",
            task = "脉冲联检" to "我会优先检查连接和告警状态。",
            exploration = "电波扫掠" to "先扫一圈，看看哪里有新的信号。",
        ),
        PetAppearance.FROST_HARE to MoteCharacterTraits(
            idle = "雪粒定格" to "我在等一个清晰、稳定的瞬间。",
            touch = "雪耳抖落" to "慢一点，我想把这一刻看清楚。",
            task = "冰晶校准" to "我来仔细核对，不让细节滑过去。",
            exploration = "霜面取景" to "稳住视线，我们先确认这束光。",
        ),
        PetAppearance.BLOOM_SPRITE to MoteCharacterTraits(
            idle = "花瓣舒展" to "家园里的新芽正在慢慢长大。",
            touch = "花苞绽放" to "你回来啦，今天也一起照料一点点。",
            task = "藤蔓牵引" to "我会把成长拆成容易照顾的小步。",
            exploration = "花粉寻踪" to "我去找找哪里适合留下新的生长。",
        ),
        PetAppearance.CRYSTAL_LIZARD to MoteCharacterTraits(
            idle = "晶棱转向" to "我在比较颜色、角度和折射。",
            touch = "晶片闪烁" to "刚才的细节折射出了新的答案。",
            task = "棱面推理" to "我会逐项比对，找出最关键的条件。",
            exploration = "光谱分辨" to "让我从光谱和方向里辨认线索。",
        ),
        PetAppearance.DUNE_FOX to MoteCharacterTraits(
            idle = "沙纹缓行" to "我在熟悉的旷野边界耐心守候。",
            touch = "沙尾拂过" to "没关系，我们有足够的耐力走下去。",
            task = "耐热推进" to "我会看住节奏，陪你坚持到终点。",
            exploration = "沙丘定向" to "先找稳定的地标，再继续深入。",
        ),
        PetAppearance.SHADOW_MOTH to MoteCharacterTraits(
            idle = "影翼静伏" to "我会保持安静，留意远处的动静。",
            touch = "影翅轻合" to "我在，不需要把每件事都说出来。",
            task = "暗线跟进" to "我会悄悄跟进，不让重要事项漏掉。",
            exploration = "暗处观察" to "我先观察边缘，不惊扰眼前的线索。",
        ),
    )

    fun resolve(appearance: PetAppearance, moment: MoteMoment, relationshipLevel: Int): MoteCharacterCue {
        val profile = MoteProfiles.profile(appearance)
        val character = traits[profile.id] ?: traits.getValue(PetAppearance.MOTE)
        val stage = when (relationshipLevel.coerceAtLeast(1)) {
            1 -> MoteRelationshipStage.FIRST_MEETING
            2 -> MoteRelationshipStage.FAMILIAR
            in 3..4 -> MoteRelationshipStage.TRUSTED
            else -> MoteRelationshipStage.BONDED
        }
        val (gesture, phrase) = when (moment) {
            MoteMoment.IDLE -> character.idle
            MoteMoment.TOUCH -> character.touch
            MoteMoment.TASK -> character.task
            MoteMoment.EXPLORATION -> character.exploration
        }
        val address = when (stage) {
            MoteRelationshipStage.FIRST_MEETING -> "新伙伴"
            MoteRelationshipStage.FAMILIAR -> "搭档"
            MoteRelationshipStage.TRUSTED -> "默契搭档"
            MoteRelationshipStage.BONDED -> "最信任的伙伴"
        }
        val opening = when (stage) {
            MoteRelationshipStage.FIRST_MEETING -> "刚认识，"
            MoteRelationshipStage.FAMILIAR -> "$address，"
            MoteRelationshipStage.TRUSTED -> "$address，"
            MoteRelationshipStage.BONDED -> "$address，"
        }
        val intensity = when (stage) {
            MoteRelationshipStage.FIRST_MEETING -> .72f
            MoteRelationshipStage.FAMILIAR -> .86f
            MoteRelationshipStage.TRUSTED -> 1f
            MoteRelationshipStage.BONDED -> 1.16f
        }
        return MoteCharacterCue(
            name = profile.name,
            moment = moment,
            relationshipStage = stage,
            address = address,
            gesture = gesture,
            line = "$opening$phrase",
            movementIntensity = intensity,
        )
    }
}
