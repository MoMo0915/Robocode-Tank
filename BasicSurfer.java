package wiki;

import robocode.*;
import robocode.util.Utils;
import java.awt.geom.*;     // 用于Point2D类
import java.lang.*;         // 用于Double和Integer对象
import java.util.ArrayList; // 用于波浪集合

public class BasicSurfer extends AdvancedRobot {
    public static int BINS = 47; // 危险区域分桶数
    public static double _surfStats[] = new double[BINS]; // 使用47个分桶统计危险区域
    public Point2D.Double _myLocation;     // 本机位置坐标
    public Point2D.Double _enemyLocation;  // 敌方位置坐标

    public ArrayList _enemyWaves; // 敌方子弹波集合
    public ArrayList _surfDirections; // 波浪方向集合
    public ArrayList _surfAbsBearings; // 绝对方位角集合

    // 需要跟踪敌方能量水平以检测能量下降(表示开火)
    public static double _oppEnergy = 100.0;

    // 表示800x600战场的矩形区域
    // 用于简单的迭代式墙壁平滑方法(由Kawigi提出)
    // 如果不熟悉墙壁平滑，wall stick表示我们尝试在坦克两端保持的空间大小
    // (向前或向后延伸)以避免触碰墙壁
    public static Rectangle2D.Double _fieldRect
            = new java.awt.geom.Rectangle2D.Double(18, 18, 764, 564);
    public static double WALL_STICK = 160; // 墙壁粘附距离

    public void run() {
        _enemyWaves = new ArrayList();
        _surfDirections = new ArrayList();
        _surfAbsBearings = new ArrayList();

        setAdjustGunForRobotTurn(true); // 设置炮塔独立于车身旋转
        setAdjustRadarForGunTurn(true); // 设置雷达独立于炮塔旋转

        do {
            // 基础迷你雷达代码
            turnRadarRightRadians(Double.POSITIVE_INFINITY); // 雷达无限旋转
        } while (true);
    }

    public void onScannedRobot(ScannedRobotEvent e) {
        _myLocation = new Point2D.Double(getX(), getY()); // 更新本机位置

        // 本机相对于敌方的横向速度分量
        double lateralVelocity = getVelocity()*Math.sin(e.getBearingRadians());
        // 计算绝对方位角
        double absBearing = e.getBearingRadians() + getHeadingRadians();

        // 雷达锁定敌人
        setTurnRadarRightRadians(Utils.normalRelativeAngle(absBearing - getRadarHeadingRadians()) * 2);

        // 记录移动方向(1表示右，-1表示左)
        _surfDirections.add(0, new Integer((lateralVelocity >= 0) ? 1 : -1));
        // 记录绝对方位角(加上π弧度)
        _surfAbsBearings.add(0, new Double(absBearing + Math.PI));

        // 通过能量差检测敌方是否开火
        double bulletPower = _oppEnergy - e.getEnergy();
        if (bulletPower < 3.01 && bulletPower > 0.09 && _surfDirections.size() > 2) {
            EnemyWave ew = new EnemyWave();
            ew.fireTime = getTime() - 1; // 开火时间(上一tick)
            ew.bulletVelocity = bulletVelocity(bulletPower); // 子弹速度
            ew.distanceTraveled = bulletVelocity(bulletPower); // 已飞行距离
            ew.direction = ((Integer)_surfDirections.get(2)).intValue(); // 方向
            ew.directAngle = ((Double)_surfAbsBearings.get(2)).doubleValue(); // 直接角度
            ew.fireLocation = (Point2D.Double)_enemyLocation.clone(); // 开火位置(上一tick的敌方位置)

            _enemyWaves.add(ew); // 添加到波浪集合
        }

        _oppEnergy = e.getEnergy(); // 更新敌方能量

        // 在EnemyWave检测后更新位置，因为波浪需要上一tick的敌方位置作为波源
        _enemyLocation = project(_myLocation, absBearing, e.getDistance());

        updateWaves(); // 更新所有波浪状态
        doSurfing();  // 执行冲浪躲避

        // 此处可以添加炮塔控制代码...
    }

    public void updateWaves() {
        for (int x = 0; x < _enemyWaves.size(); x++) {
            EnemyWave ew = (EnemyWave)_enemyWaves.get(x);

            // 更新波浪已传播距离
            ew.distanceTraveled = (getTime() - ew.fireTime) * ew.bulletVelocity;
            // 如果波浪已传播超过本机位置50像素以上，则移除该波浪
            if (ew.distanceTraveled > _myLocation.distance(ew.fireLocation) + 50) {
                _enemyWaves.remove(x);
                x--;
            }
        }
    }

    // 获取最近的可冲浪波浪
    public EnemyWave getClosestSurfableWave() {
        double closestDistance = 50000; // 初始设为很大的数值
        EnemyWave surfWave = null;

        for (int x = 0; x < _enemyWaves.size(); x++) {
            EnemyWave ew = (EnemyWave)_enemyWaves.get(x);
            // 计算波浪与本机的距离
            double distance = _myLocation.distance(ew.fireLocation) - ew.distanceTraveled;

            // 找到最近的有效波浪
            if (distance > ew.bulletVelocity && distance < closestDistance) {
                surfWave = ew;
                closestDistance = distance;
            }
        }

        return surfWave;
    }

    // 根据敌方波浪和被击中的位置，计算对应的危险统计数组索引
    public static int getFactorIndex(EnemyWave ew, Point2D.Double targetLocation) {
        // 计算偏移角度
        double offsetAngle = (absoluteBearing(ew.fireLocation, targetLocation) - ew.directAngle);
        // 标准化并计算因子
        double factor = Utils.normalRelativeAngle(offsetAngle) / maxEscapeAngle(ew.bulletVelocity) * ew.direction;

        // 限制在0到BINS-1范围内
        return (int)limit(0, (factor * ((BINS - 1) / 2)) + ((BINS - 1) / 2), BINS - 1);
    }

    // 根据被击中的波浪和位置更新危险统计
    public void logHit(EnemyWave ew, Point2D.Double targetLocation) {
        int index = getFactorIndex(ew, targetLocation);

        // 对命中区域进行高斯式扩散标记
        for (int x = 0; x < BINS; x++) {
            // 命中点bin加1，相邻bin加1/2，再远加1/5，以此类推
            _surfStats[x] += 1.0 / (Math.pow(index - x, 2) + 1);
        }
    }

    public void onHitByBullet(HitByBulletEvent e) {
        // 如果_enemyWaves为空，说明我们可能错过了检测这个波浪
        if (!_enemyWaves.isEmpty()) {
            Point2D.Double hitBulletLocation = new Point2D.Double(
                    e.getBullet().getX(), e.getBullet().getY());
            EnemyWave hitWave = null;

            // 遍历所有波浪，找出可能命中我们的那个
            for (int x = 0; x < _enemyWaves.size(); x++) {
                EnemyWave ew = (EnemyWave)_enemyWaves.get(x);

                // 通过距离和速度匹配判断
                if (Math.abs(ew.distanceTraveled - _myLocation.distance(ew.fireLocation)) < 50
                        && Math.abs(bulletVelocity(e.getBullet().getPower()) - ew.bulletVelocity) < 0.001) {
                    hitWave = ew;
                    break;
                }
            }

            if (hitWave != null) {
                logHit(hitWave, hitBulletLocation); // 记录命中

                // 命中后可以移除这个波浪
                _enemyWaves.remove(_enemyWaves.lastIndexOf(hitWave));
            }
        }
    }

    // 预测位置方法(来自Apollon的迷你预测器，作者rozu)
    public Point2D.Double predictPosition(EnemyWave surfWave, int direction) {
        Point2D.Double predictedPosition = (Point2D.Double)_myLocation.clone();
        double predictedVelocity = getVelocity();
        double predictedHeading = getHeadingRadians();
        double maxTurning, moveAngle, moveDir;

        int counter = 0; // 未来tick计数器
        boolean intercepted = false; // 是否被拦截标志

        do {
            // 计算移动角度(考虑墙壁平滑)
            moveAngle = wallSmoothing(predictedPosition,
                    absoluteBearing(surfWave.fireLocation, predictedPosition) + (direction * (Math.PI/2)),
                    direction) - predictedHeading;
            moveDir = 1;

            if(Math.cos(moveAngle) < 0) {
                moveAngle += Math.PI;
                moveDir = -1;
            }

            moveAngle = Utils.normalRelativeAngle(moveAngle);

            // 最大转向角度(每tick)
            maxTurning = Math.PI/720d*(40d - 3d*Math.abs(predictedVelocity));
            predictedHeading = Utils.normalRelativeAngle(predictedHeading +
                    limit(-maxTurning, moveAngle, maxTurning));

            // 速度变化规则：如果速度和方向相反则减速，否则加速
            predictedVelocity += (predictedVelocity * moveDir < 0 ? 2*moveDir : moveDir);
            predictedVelocity = limit(-8, predictedVelocity, 8);

            // 计算新预测位置
            predictedPosition = project(predictedPosition, predictedHeading, predictedVelocity);

            counter++;

            // 检查是否会被子弹拦截
            if (predictedPosition.distance(surfWave.fireLocation) <
                    surfWave.distanceTraveled + (counter * surfWave.bulletVelocity)
                            + surfWave.bulletVelocity) {
                intercepted = true;
            }
        } while(!intercepted && counter < 500);

        return predictedPosition;
    }

    // 检查指定方向的危险值
    public double checkDanger(EnemyWave surfWave, int direction) {
        int index = getFactorIndex(surfWave, predictPosition(surfWave, direction));
        return _surfStats[index];
    }

    // 执行冲浪躲避动作
    public void doSurfing() {
        EnemyWave surfWave = getClosestSurfableWave(); // 获取最近的波浪

        if (surfWave == null) { return; } // 无波浪则返回

        // 计算左右两侧的危险值
        double dangerLeft = checkDanger(surfWave, -1);
        double dangerRight = checkDanger(surfWave, 1);

        // 计算基础移动角度
        double goAngle = absoluteBearing(surfWave.fireLocation, _myLocation);
        // 选择危险较小的方向
        if (dangerLeft < dangerRight) {
            goAngle = wallSmoothing(_myLocation, goAngle - (Math.PI/2), -1); // 向左移动
        } else {
            goAngle = wallSmoothing(_myLocation, goAngle + (Math.PI/2), 1); // 向右移动
        }

        setBackAsFront(this, goAngle); // 执行移动
    }

    // 敌方波浪内部类
    class EnemyWave {
        Point2D.Double fireLocation; // 开火位置
        long fireTime;               // 开火时间
        double bulletVelocity;       // 子弹速度
        double directAngle;          // 直接角度
        double distanceTraveled;     // 已传播距离
        int direction;               // 方向(1或-1)

        public EnemyWave() { }
    }

    // 迭代式墙壁平滑方法(由Kawigi提出)
    // 返回考虑墙壁平滑后的绝对移动角度
    public double wallSmoothing(Point2D.Double botLocation, double angle, int orientation) {
        // 微调角度直到不会撞墙
        while (!_fieldRect.contains(project(botLocation, angle, 160))) {
            angle += orientation*0.05;
        }
        return angle;
    }

    // 从源位置按指定角度和距离投影新点(来自CassiusClay，作者PEZ)
    public static Point2D.Double project(Point2D.Double sourceLocation, double angle, double length) {
        return new Point2D.Double(sourceLocation.x + Math.sin(angle) * length,
                sourceLocation.y + Math.cos(angle) * length);
    }

    // 计算从源点到目标点的绝对角度(弧度)(来自RaikoMicro，作者Jamougha)
    public static double absoluteBearing(Point2D.Double source, Point2D.Double target) {
        return Math.atan2(target.x - source.x, target.y - source.y);
    }

    // 数值限制函数
    public static double limit(double min, double value, double max) {
        return Math.max(min, Math.min(value, max));
    }

    // 根据子弹能量计算子弹速度
    public static double bulletVelocity(double power) {
        return (20D - (3D*power));
    }

    // 计算最大逃脱角度
    public static double maxEscapeAngle(double velocity) {
        return Math.asin(8.0/velocity);
    }

    // 设置前进或后退(保持指定角度)
    public static void setBackAsFront(AdvancedRobot robot, double goAngle) {
        double angle = Utils.normalRelativeAngle(goAngle - robot.getHeadingRadians());
        if (Math.abs(angle) > (Math.PI/2)) {
            // 需要后退
            if (angle < 0) {
                robot.setTurnRightRadians(Math.PI + angle);
            } else {
                robot.setTurnLeftRadians(Math.PI - angle);
            }
            robot.setBack(100);
        } else {
            // 需要前进
            if (angle < 0) {
                robot.setTurnLeftRadians(-1*angle);
            } else {
                robot.setTurnRightRadians(angle);
            }
            robot.setAhead(100);
        }
    }

    // 绘制方法(用于调试)
    public void onPaint(java.awt.Graphics2D g) {
        g.setColor(java.awt.Color.red);
        for(int i = 0; i < _enemyWaves.size(); i++){
            EnemyWave w = (EnemyWave)(_enemyWaves.get(i));
            Point2D.Double center = w.fireLocation;

            // 计算波浪半径
            int radius = (int)w.distanceTraveled;

            // 只在波浪接近本机时绘制(半径-40 < 距离中心)
            if(radius - 40 < center.distance(_myLocation))
                g.drawOval((int)(center.x - radius), (int)(center.y - radius), radius*2, radius*2);
        }
    }
}