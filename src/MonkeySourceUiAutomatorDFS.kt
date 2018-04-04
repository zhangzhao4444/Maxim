package tv.panda.test.monkey

import android.app.ActivityManager
import android.app.UiAutomation
import android.app.UiAutomationConnection
import android.content.ComponentName
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.hardware.display.DisplayManagerGlobal
import android.os.HandlerThread
import android.os.RemoteException
import android.os.SystemClock
import android.util.ArraySet
import android.view.Display
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.w3c.dom.Document
import tv.panda.test.monkey.ape.model.*
import tv.panda.test.monkey.ape.tree.Tree
import tv.panda.test.monkey.ape.tree.TreeNode
import tv.panda.test.monkey.ape.utils.*
import java.io.File
import java.util.*
import kotlin.collections.ArrayList

class MonkeySourceUiAutomatorDFS(private val device: AndroidDevice, private val mRandom: Random, private val mMainApps: List<ComponentName>,
                                 throttle: Long, randomizeThrottle: Boolean, permissionTargetSystem: Boolean, private val mSpecialEventController: SpecialEventController, private val mBlackWidgetController: BlackWidgetController, outputDirectory: String) : MonkeyEventSource {

    private val mOutputDirectory = outputDirectory
    private val mDevice: AndroidDevice = device
    private val mRandomizeThrottle = randomizeThrottle
    private val activityClassNames: MutableSet<String> = mutableSetOf()
    private var mEventCount = 0
    private var mPoint: PointF? = null
    private var mAction: String = ""
    private var mSpecialEventCount = 0
    private val mQ: MonkeyEventQueue
    private var mVerbose = 0
    private val mThrottle: Long = throttle
    private val mPermissionUtil: MonkeyPermissionUtil
    private val mKeyboardOpen = false
    private var currentState: State? = null
    private var currentAction: Action? = null
    private val states: MutableSet<State>
    private val stateToTransitions: MutableMap<State, MutableSet<Action>>
    private val transitionsToTargets: MutableMap<Action, MutableSet<State>>
    private val mHandlerThread = HandlerThread("MonkeySourceUiAutomatorDFS")
    private var mUiAutomation: UiAutomation? = null
    private val GESTURE_TAP = 0
    private val GESTURE_DRAG = 1
    private val GESTURE_PINCH_OR_ZOOM = 2
    private var nullInfoCounter = 0
    private var actionsHistory: ArrayList<Any> = ArrayList()
    private var lastInputTimestamp:Int = 0
    private val wakeupAfterNSecondsofsleep = Config.getLong("max.wakeupAfterNSecondsofsleep", 4000)
    private val startAfterNSecondsofsleep = Config.getLong("max.startAfterNSecondsofsleep", 6000)
    private val UIAutomatorFailNSecondsofsleep = Config.getLong("max.UIAutomatorFailNSecondsofsleep", 1000)
    private val randomPickFromStringList = Config.getBoolean("max.randomPickFromStringList", false)
    private val screenShotAndSavePageSource = Config.getBoolean("max.screenShotAndSavePageSource", false)
    private val saveCurrentEventPoint = Config.getBoolean("max.saveCurrentEventPoint", false)
    private var mImageWriters: MutableList<ImageWriterQueue> = mutableListOf()
    private var mCurrentActivity = ""
    private var maxrq = 0
    private var keepTime: Long = SystemClock.elapsedRealtime()

    private val rootInActiveWindow: AccessibilityNodeInfo?
        get() = mUiAutomation?.rootInActiveWindow

    override val nextEvent: MonkeyEvent?
        get() {
            checkActivity()
            if (mQ.isEmpty()) {
                generateEvents()
            }
            mEventCount++
            val e = mQ.first
            mQ.removeFirst()
            return e
        }

    internal interface NodeVisitor<E> {
        val data: E
        fun visit(node: Node): Boolean
    }

    internal class Node(var id: Int, var properties: Map<String, String>, info: AccessibilityNodeInfo) {
        private var children: ArrayList<Node> = ArrayList()
        var bounds = Rect()

        val isLongClickable: Boolean
            get() = if(properties.containsKey("longClickable")) properties["longClickable"]!!.toBoolean() else false

        val isClickable: Boolean
            get() = if(properties.containsKey("clickable")) properties["clickable"]!!.toBoolean() else false


        init {
            info.getBoundsInScreen(bounds)
        }

        fun addChild(node: Node) {
            children.add(node)
        }

        private fun toString(sb: StringBuilder) {
            sb.append('{')
            for (e in properties) {
                if (e.key =="packageName" || e.key == "text" || e.value == null) continue
                Logger.log("properties= ${e.key}")
                Logger.log("properties= ${e.value}")
                sb.append("${e.key}:${e.value},")
            }
            sb.append("children:[")
            for (child in children) {
                child.toString(sb)
                sb.append(',')
            }
            sb.append("]}");
        }

        override fun toString(): String {
            val sb = StringBuilder()
            toString(sb)
            return sb.toString()
        }

        fun visitNode(visitor: NodeVisitor<*>): Boolean {
            var cont = visitor.visit(this)
            if (cont) {
                for (child in children) {
                    cont = child.visitNode(visitor)
                    if (!cont) {
                        return cont
                    }
                }
            }
            return cont
        }
    }

    internal class State {
        private var root: Node? = null
        private var stateString: String

        constructor(node: Node) {
            this.root = node
            this.stateString = node.toString()
        }

        private constructor(state: String) {
            this.stateString = state
        }

        override fun toString(): String {
            return stateString
        }

        override fun hashCode(): Int {
            return stateString.hashCode()
        }

        fun visitNode(visitor: NodeVisitor<*>) {
            root?.visitNode(visitor)
        }

        override fun equals(obj: Any?): Boolean {
            if (this === obj)
                return true
            if (obj == null)
                return false
            if (javaClass != obj.javaClass)
                return false
            val other = obj as State?
            return other!!.stateString == stateString
        }

        companion object {
            fun newInitialState(): State {
                return State("<init>")
            }
        }
    }

    internal enum class ActionType {
        START,
        BACK,
        CLICK,
        LONG_CLICK
    }

    internal class Action(private var state: State?, var node: Node?, var actionType: ActionType?) {

        override fun hashCode(): Int {
            val prime = 31
            var result = 1
            result = prime * result + if (actionType == null) 0 else actionType!!.hashCode()
            result = prime * result + if (node == null) 0 else node!!.id
            result = prime * result + if (state == null) 0 else state!!.hashCode()
            return result
        }

        override fun equals(obj: Any?): Boolean {
            if (this === obj)
                return true
            if (obj == null)
                return false
            if (javaClass != obj.javaClass)
                return false
            val other = obj as Action?
            if (actionType != other!!.actionType)
                return false
            if (node == null) {
                if (other.node != null) {
                    return false
                }
            } else if (other.node == null) {
                return false
            } else if (node!!.id != other.node!!.id) {
                return false
            }
            if (state == null) {
                if (other.state != null)
                    return false
            } else if (state != other.state)
                return false
            return true
        }
    }

    internal abstract class NodeBuilder {
        private var id = 0

        internal abstract fun buildAttributes(input: AccessibilityNodeInfo): Map<String, String>

        internal abstract fun buildNode(input: AccessibilityNodeInfo): Node

        fun buildTree(root: AccessibilityNodeInfo): Node {
            val rootNode = buildNode(root)
            try{
                repeat(root.childCount) {
                    val child = try { root.getChild(it) } catch (e: Exception){null}
                    if (child != null){
                        if (child.isVisibleToUser) {
                            val childNode = buildTree(child)
                            rootNode.addChild(childNode)
                            child.recycle()
                        }
                    }
                }
            }catch (e:Exception){
                Logger.error("// : $e")
            }
            return rootNode
        }

        fun nextId(): Int {
            return id++
        }

        fun id(): Int {
            return id
        }
    }

    internal class OnlyLayoutBuilder : NodeBuilder() {
        public override fun buildNode(input: AccessibilityNodeInfo): Node {
            val attributes = buildAttributes(input)
            return Node(nextId(), attributes, input)
        }

        public override fun buildAttributes(input: AccessibilityNodeInfo): Map<String, String> {
            val attributes = HashMap<String, String>()
            try{
                attributes.put("className", "${input.className}")
                attributes.put("packageName", "${input.packageName}")
                attributes.put("text", "${input.text}")
                attributes.put("viewIdResourceName", input.viewIdResourceName)
                if (input.isCheckable) attributes.put("checkable", "true")
                if (input.isClickable) attributes.put("clickable", "true")
                if (input.isContentInvalid) attributes.put("contentInvalid", "true")
                if (input.isContextClickable) attributes.put("contextClickable", "true")
                if (input.isDismissable) attributes.put("dismissable", "true")
                if (input.isEditable) attributes.put("editable", "true")
                if (input.isLongClickable) attributes.put("longClickable", "true")
                if (input.isPassword) attributes.put("password", "true")
                if (input.isScrollable) attributes.put("scrollable", "true")
            }catch (e:Exception){
                Logger.error("// : $e")
            }
            return attributes
        }
    }

    private fun startImageWriter(){
        if (mVerbose > 3) Logger.log("//debug, screenShotAndSavePageSource = $screenShotAndSavePageSource, throttle = $mThrottle")
        if (mThrottle <= 200 || !screenShotAndSavePageSource ) return
        var i = 0
        while (i < 3) {
            mImageWriters.add(ImageWriterQueue())
            Thread(mImageWriters[i]).start()
            i++
        }
    }

    private fun connect() {
        if (mHandlerThread.isAlive) {
            throw IllegalStateException("Already connected!")
        }
        try{
            if(mVerbose > 1) Logger.log("// Start Connect UiAutomation")
            mHandlerThread.start()
            mUiAutomation = UiAutomation(mHandlerThread.looper, UiAutomationConnection())
            mUiAutomation!!.connect()
        }catch (e: Exception){
            e.printStackTrace()
        }
    }

    fun disconnect() {
        if (!mHandlerThread.isAlive) {
            throw IllegalStateException("Already disconnected!")
        }
        mUiAutomation?.disconnect()
        mHandlerThread.quit()
    }

    init {
        startImageWriter()
        connect()
        mQ = MonkeyEventQueue(mRandom, throttle, randomizeThrottle)
        mPermissionUtil = MonkeyPermissionUtil()
        mPermissionUtil.setTargetSystemPackages(permissionTargetSystem)

        currentState = State.newInitialState()
        states = HashSet()
        states.add(currentState!!)
        currentAction = Action(currentState, null, ActionType.START)
        stateToTransitions = HashMap()
        transitionsToTargets = HashMap()
    }

    private fun randomPoint(random: Random, display: Display): PointF {
        return PointF(random.nextInt(display.width).toFloat(), random.nextInt(display.height).toFloat())
    }

    private fun randomVector(random: Random): PointF {
        return PointF((random.nextFloat() - 0.5f) * 50, (random.nextFloat() - 0.5f) * 50)
    }

    private fun randomWalk(random: Random, display: Display, point: PointF, vector: PointF) {
        point.x = Math.max(Math.min(point.x + random.nextFloat() * vector.x,
                display.width.toFloat()), 0f).toFloat()
        point.y = Math.max(Math.min(point.y + random.nextFloat() * vector.y,
                display.height.toFloat()), 0f).toFloat()
    }

    private fun generateClickEventAt(nodeRect: Rect, waitTime: Long) {
        try {
            val display = DisplayManagerGlobal.getInstance().getRealDisplay(Display.DEFAULT_DISPLAY)
            val size = Point()
            display.getSize(size)

            val bounds = getVisibleBoundsInScreen(nodeRect, size.x, size.y)
            val p1 = PointF(bounds.exactCenterX(), bounds.exactCenterY())
            mPoint = p1
            mAction = "Point"

            val downAt = SystemClock.uptimeMillis()

            mQ.addLast(MonkeyTouchEvent(MotionEvent.ACTION_DOWN)
                    .setDownTime(downAt)
                    .addPointer(0, p1.x, p1.y)
                    .setIntermediateNote(false))

            if (waitTime > 0) {
                val we = MonkeyWaitEvent(waitTime)
                mQ.addLast(we)
            }

            mQ.addLast(MonkeyTouchEvent(MotionEvent.ACTION_UP)
                    .setDownTime(downAt)
                    .addPointer(0, p1.x, p1.y)
                    .setIntermediateNote(false))
        }catch (e: RuntimeException){
            e.printStackTrace()
        }catch (e: Exception){
            e.printStackTrace()
        }
    }

    private fun generateKeyBackEvent() {
        generateKeyEvent(KeyEvent.KEYCODE_BACK)
    }

    private fun generateKeyEvent(key: Int) {
        var e = MonkeyKeyEvent(KeyEvent.ACTION_DOWN, key)
        mQ.addLast(e)

        e = MonkeyKeyEvent(KeyEvent.ACTION_UP, key)
        mQ.addLast(e)
    }

    private fun generateGUIEvent(info: AccessibilityNodeInfo) {
        val builder = OnlyLayoutBuilder()
        val rootNode = builder.buildTree(info)

        Logger.log("// Current NO.${builder.id()} Tree")
        try {
            val state = State(rootNode)
            val added = states.add(state)
            val ret = addToMapSet(transitionsToTargets, currentAction, state)
            currentState = state
            var actions: Set<Action>? = stateToTransitions[currentState!!]
            if (actions == null) {
                val visitor = object : NodeVisitor<Set<Action>> {
                    internal var act: MutableSet<Action> = HashSet()

                    override val data: MutableSet<Action>
                        get() = act

                    override fun visit(node: Node): Boolean {
                        val types = getActionTypes(node)
                        for (type in types) {
                            val action = Action(currentState, node, type)
                            if (transitionsToTargets.containsKey(action)) {
                                throw RuntimeException("Should not be true")
                            }
                            act.add(action)
                        }
                        return true
                    }
                }

                state.visitNode(visitor)
                actions = visitor.data
                stateToTransitions.put(state, actions)
            }

            if (actions.isEmpty()) {
                currentAction = Action(currentState, null, ActionType.BACK)
                generateKeyBackEvent()
                actionsHistory.add("Back")
                return
            }

            for (act in actions) {
                if (!transitionsToTargets.containsKey(act)) {
                    generateEventForAction(act)
                    currentAction = act
                    actionsHistory.add(act)
                    return
                }
            }

            val queue = LinkedList<List<Action>>()
            val visited = HashSet<State>()

            visited.add(state)
            for (act in actions) {
                val current = ArrayList<Action>()
                current.add(act)
                queue.addLast(current)
            }

            var result: List<Action>? = null
            while (!queue.isEmpty()) {
                val current = queue.removeFirst()
                if (current.isEmpty()) {
                    throw RuntimeException("Should not be empty")
                }
                val last = current[current.size - 1]
                val targets = transitionsToTargets[last]
                if (targets == null) {
                    result = current
                    break
                }
                for (s in targets) {
                    if (visited.add(s)) {
                        val to = stateToTransitions[s] ?: throw RuntimeException("a visited state should not have transitions")
                        for (act in to) {
                            val newList = ArrayList<Action>(current.size + 1)
                            newList.addAll(current)
                            newList.add(act)
                            queue.addLast(newList)
                        }
                    }
                }
            }

            if (result != null && result.isNotEmpty()) {
                currentAction = result[0]
                generateEventForAction(result[0])
                actionsHistory.add(result[0])
                return
            }

            currentAction = Action(currentState, null, ActionType.BACK)
            generateKeyBackEvent()
        }catch (e: Exception){
            e.printStackTrace()
        }
        actionsHistory.add("Back")
    }

    private fun generateEventForAction(action: Action) {
        when (action.actionType) {
            ActionType.CLICK -> generateClickEventAt(action.node!!.bounds, 0L)
            ActionType.LONG_CLICK -> generateClickEventAt(action.node!!.bounds, 2000L)
            else -> throw RuntimeException("Should not reach here")
        }
    }

    private fun getActionTypes(node: Node): List<ActionType> {
        val types = ArrayList<ActionType>()

        if (node.isClickable) {
            types.add(ActionType.CLICK)
        }
        if (node.isLongClickable) {
            types.add(ActionType.LONG_CLICK)
        }
        return types
    }


    private fun generatePointerEvent(random: Random, gesture: Int) {
        val display = DisplayManagerGlobal.getInstance().getRealDisplay(Display.DEFAULT_DISPLAY)
        var p1 = randomPoint(random, display)
        mPoint = p1
        mAction = "Point"

        val v1 = randomVector(random)
        val downAt = SystemClock.uptimeMillis()

        mQ.addLast(MonkeyTouchEvent(MotionEvent.ACTION_DOWN)
                .setDownTime(downAt)
                .addPointer(0, p1.x, p1.y)
                .setIntermediateNote(false))

        if (gesture == GESTURE_DRAG) {
            val count = random.nextInt(10)
            for (i in 0 until count) {
                randomWalk(random, display, p1, v1)

                mQ.addLast(MonkeyTouchEvent(MotionEvent.ACTION_MOVE)
                        .setDownTime(downAt)
                        .addPointer(0, p1.x, p1.y)
                        .setIntermediateNote(true))
            }
        } else if (gesture == GESTURE_PINCH_OR_ZOOM) {
            val p2 = randomPoint(random, display)
            val v2 = randomVector(random)

            randomWalk(random, display, p1, v1)
            mQ.addLast(MonkeyTouchEvent(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
                    .setDownTime(downAt)
                    .addPointer(0, p1.x, p1.y).addPointer(1, p2.x, p2.y)
                    .setIntermediateNote(true))

            val count = random.nextInt(10)
            for (i in 0 until count) {
                randomWalk(random, display, p1, v1)
                randomWalk(random, display, p2, v2)

                mQ.addLast(MonkeyTouchEvent(MotionEvent.ACTION_MOVE)
                        .setDownTime(downAt)
                        .addPointer(0, p1.x, p1.y).addPointer(1, p2.x, p2.y)
                        .setIntermediateNote(true))
            }

            randomWalk(random, display, p1, v1)
            randomWalk(random, display, p2, v2)
            mQ.addLast(MonkeyTouchEvent(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
                    .setDownTime(downAt)
                    .addPointer(0, p1.x, p1.y).addPointer(1, p2.x, p2.y)
                    .setIntermediateNote(true))
        }

        randomWalk(random, display, p1, v1)
        mQ.addLast(MonkeyTouchEvent(MotionEvent.ACTION_UP)
                .setDownTime(downAt)
                .addPointer(0, p1.x, p1.y)
                .setIntermediateNote(false))
    }

    private fun generateEvents() {
        if (!mQ.isEmpty()) {
            return
        }
        val info = rootInActiveWindow
        if (info != null) {
            checkVirtualKeyboard()
            generateGUIEvent(info)

            if (isBlocked()){
                stopTopActivity()
                generateThrottleEvent(startAfterNSecondsofsleep)
            }
            nullInfoCounter = 0;
            info.recycle()
            return
        } else {
            Logger.error(" // : null root node returned by UiTestAutomationBridge(${nullInfoCounter+1} times), use default events generator.")
            ++nullInfoCounter
            repeat(Random().nextInt(10)){
                generatePointerEvent(mRandom, GESTURE_DRAG)
            }
            this.generateThrottleEvent(UIAutomatorFailNSecondsofsleep)
            if (nullInfoCounter > 50) {
                stopTopActivity()
                nullInfoCounter = 0
                this.generateThrottleEvent(startAfterNSecondsofsleep)
            }
            if (!mDevice.checkInteractive()){
                generateActivateEvent()
                generateThrottleEvent(wakeupAfterNSecondsofsleep)
                generatePointerEvent(mRandom, GESTURE_TAP)
            }
        }

        if (mQ.isEmpty()) {
            generateThrottleEvent(100)
        }
    }

    override fun validate(): Boolean {
        return mHandlerThread.isAlive
    }

    override fun setVerbose(verbose: Int) {
        mVerbose = verbose
    }

    protected fun generateThrottleEvent(base: Long) {
        var throttle = base
        if (this.mRandomizeThrottle && this.mThrottle > 0) {
            throttle = this.mRandom.nextLong()
            if (throttle < 0) {
                throttle = -throttle
            }
            throttle = throttle % base + 1
        }
        if (throttle < 0) {
            throttle = -throttle
        }
        this.mQ.addLast(MonkeyThrottleEvent(throttle))
    }

    fun generateActivity() {
        val e = MonkeyActivityEvent(mMainApps[mRandom.nextInt(mMainApps.size)])
        mQ.addLast(e)
        this.generateThrottleEvent(startAfterNSecondsofsleep)
    }

    fun getTopActivityComponentName(): ComponentName? {
        var cn: ComponentName?
        try {
            cn = this.mDevice.mAm.getTasks(1, 0).get(0).topActivity
        } catch (e: RemoteException) {
            e.printStackTrace()
            cn = null
        }
        return cn
    }

    private fun rebuildState(){
        states.clear()
        transitionsToTargets.clear()
        stateToTransitions.clear()
    }

    fun checkActivity(): Boolean {
        var ret = true
        var currentPackage = Monkey.currentPackage

        val cn = this.getTopActivityComponentName()

        if (cn != null) currentPackage = cn.packageName

        if (!MonkeyUtils.packageFilter.isPackageValid(currentPackage!!)) {
            Logger.error("// : the top activity package $currentPackage is invalid.")
            this.mQ.clear()
            this.generateActivity()
            if (actionsHistory.lastOrNull() != null && actionsHistory.last() == "Back" && actionsHistory.size >2 && actionsHistory[actionsHistory.size-2] == "Back") rebuildState()
            actionsHistory.add("startActivity")
            ret = false
        }else if(cn != null){
            val className = cn.className
            this.activityClassNames.add(className)
            if (this.mCurrentActivity != className){
                this.mCurrentActivity = className
                keepTime = SystemClock.elapsedRealtime()
                if (mVerbose > 1) Logger.log("// : debug, currentActivity is ${this.mCurrentActivity}")
                mSpecialEventCount ++
            }
            if (className.toLowerCase().contains("crash")) {
                Logger.error("// : We caputure an activity [$className] whose name contains \"crash\" at ${Date()}.")
            }
        }
        return ret
    }

    fun tearDown() {
        val size = this.activityClassNames.size;
        Logger.log("// Total activities $size")
        var i = 1
        for (className in activityClassNames){
            Logger.log("// $i - ${className}")
            i ++
        }
    }

    private fun getVisibleBounds(): Rect {
        val rect = Rect()
        val display = DisplayManagerGlobal.getInstance().getRealDisplay(0)
        val p = Point()
        display.getSize(p)
        rect.top = 0
        rect.left = 0
        rect.right = p.x
        rect.bottom = p.y
        return rect
    }

    private fun generateActivateEvent() {
        val rect = this.getVisibleBounds()
        val x = rect.right.toFloat()
        val y = (rect.top + rect.bottom).toFloat()
        val downAt = SystemClock.uptimeMillis()
        this.mQ.addLast(MonkeyTouchEvent(0).setDownTime(downAt).addPointer(0, x, y).setIntermediateNote(false))
        this.mQ.addLast(MonkeyTouchEvent(2).setDownTime(downAt).addPointer(0, x + 5f, y + 0f).setIntermediateNote(true))
        this.mQ.addLast(MonkeyTouchEvent(2).setDownTime(downAt).addPointer(0, x, y).setIntermediateNote(false))
        this.mQ.addLast(MonkeyTouchEvent(0).setDownTime(downAt).addPointer(0, x, y).setIntermediateNote(false))
    }

    fun stopTopActivity() {
        var killed = 0
        try {
            val runningApps = this.mDevice.mAm.runningAppProcesses
            if (!runningApps.isEmpty()) {
                val obj = runningApps[0]
                Logger.log("// Try to stop process ${(obj as ActivityManager.RunningAppProcessInfo).processName}s(${obj.pid}) ")
                if (this.mDevice.killPid(obj.pid) === 0) {
                    killed = 1
                    Logger.log("// Process ${obj.processName}(${obj.pid}) is killed")
                }
            }

        } catch (e: RemoteException) {
            killed = 0
        }catch (e : Exception){
            killed = 0
        }
        if (killed == 0) {
            stopPackages()
        }

        this.mDevice.checkInteractive()
    }

    fun stopPackages() {
        val it: Iterator<ComponentName>  = this.mMainApps.iterator()
        while (it.hasNext()) {
            val obj = it.next()
            try {
                Logger.log("// Try to stop package ${obj.packageName}")
                this.mDevice.mAm.forceStopPackage(obj.packageName, 0)
            } catch (e: RemoteException) {
                e.printStackTrace()
            }

        }
    }

//    private fun dfsdump(){
//        var i=0
//        for (a in actionsHistory){
//            Logger.log("action$i = ${a.hashCode()}")
//            i++
//        }
//    }

    private fun isBlocked(): Boolean{
        try {
            val jam = true
            val realtime = SystemClock.elapsedRealtime()
            if (realtime - keepTime >= 10 * 60000){ if (mVerbose > 1) Logger.log("// Duration of more than 10 min, block!!!, ${(realtime - keepTime)/(10*60000)}")
                return jam
            }

            val size = actionsHistory.size
            val last = actionsHistory.lastOrNull()

            if (size < 40 || last == null || last == "startActivity") return false

            val list = actionsHistory.takeLast(30)
            if (last == "Back" && list.filterNot { it == last }.isEmpty()) return jam
            if (list.contains("Back") || list.contains("startActivity")) return false

            var j = 0
            for(i in 1..29){
                if (mVerbose > 1) Logger.log("last action is ${list.size-1} code = $last, action${list.size-i} code = ${list[list.size-i]}")
                if (last == list[list.size-i]) j ++
            }
            if (j >= 28) return jam

            j = 0
            val last2 = list[list.size-2]
            for(i in 1..29 step 2){
                if (mVerbose > 1) Logger.log("last action is ${list.size} code = $last, action${list.size-i} code = ${list[list.size-i]}")
                if (mVerbose > 1) Logger.log("last but one action is ${list.size-1} code = $last, action${list.size-i-1} code = ${list[list.size-i-1]}")
                if (last == list[list.size-i] && last2 == list[list.size-i-1])  j ++
            }
            if (j >= 14) return jam
        }catch (e: Exception){
            e.printStackTrace()
            return false
        }
        return false
    }

    fun checkVirtualKeyboard(): Boolean {
        var ret = false
        try {
            val inputMethodVisbleHight = this.mDevice.mIMM!!.getInputMethodWindowVisibleHeight()
            if (inputMethodVisbleHight <= 0) return ret

            Logger.log("// InputMethodVisibleHeight: $inputMethodVisbleHight, lastInput=$lastInputTimestamp, current=${mEventCount}")
            if (lastInputTimestamp == mEventCount)  return ret
            lastInputTimestamp = mEventCount

            if (randomPickFromStringList) {
                generateKeyEventsForString()
            }
            ret = true
        } catch (e: RemoteException) {
            e.printStackTrace()
            Logger.error("// Fail to getInputMethodWindowVisibleHeight")
        }

        return ret
    }

    private fun generateKeyEventsForString() {
        this.mDevice.sendText(String.nextString())
    }

    companion object {

        internal fun <K1, K2> addToMapSet(map: MutableMap<K1, MutableSet<K2>>, k1: K1?, k2: K2): Boolean {
            var result: MutableSet<K2>? = map[k1]
            if (result == null) {
                result = HashSet()
                map.put(k1!!, result)
            }
            return result.add(k2)
        }

        fun getVisibleBoundsInScreen(nodeRect: Rect, width: Int, height: Int): Rect {
            val displayRect = Rect()
            displayRect.top = 0
            displayRect.left = 0
            displayRect.right = width
            displayRect.bottom = height

            nodeRect.intersect(displayRect)
            return nodeRect
        }
    }
}
