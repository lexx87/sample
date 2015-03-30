package ru.it.home.qms.ejb;

import org.apache.commons.collections4.map.MultiValueMap;
import ru.it.home.common.bean.ApplicationStateBean;
import ru.it.home.common.ejb.CoreBean;
import ru.it.home.common.event.AppointmentVisitorChangeEvent;
import ru.it.home.common.event.CriticalObjectChangeEvent;
import ru.it.home.common.event.DepartmentScheduleChangedEvent;
import ru.it.home.common.event.SmsDeliveryStatusEvent;
import ru.it.home.common.event.type.ChangeType;
import ru.it.home.common.event.type.EntityTypeQualifier;
import ru.it.home.pz.ejb.AppointmentSingletonBean;
import ru.it.home.pz.ejb.RegistrationSingletonBean;
import ru.it.home.qms.bean.AlgorithmBean;
import ru.it.home.qms.ejb.exception.AlgorithmTestException;
import ru.it.home.qms.ejb.exception.LiveQueueNotAllowedException;
import ru.it.home.qms.ejb.exception.MaxNumberVisitorsException;
import ru.it.home.qms.enums.AppointmentStatus;
import ru.it.home.qms.enums.MaintenanceMode;
import ru.it.home.qms.enums.VisitorStatus;
import ru.it.home.qms.enums.WorkplaceType;
import ru.it.home.qms.event.*;
import ru.it.home.qms.jpa.*;
import ru.it.home.qms.jpa.Schedule;
import ru.it.home.qms.utils.LiveQueueDistributionType;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.ejb.*;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Event;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.util.Calendar.getInstance;
import static java.util.logging.Logger.getLogger;
import static ru.it.home.qms.utils.DateHelper.addNMinuteToDate;

/**
 * все что касается очередей
 *
 */
@Singleton
@ApplicationScoped
@LocalBean
@Lock(LockType.READ)
@Startup
public class QueueSingletonBean implements Serializable {

    //Сгенерировать водяной знак
    private static final Random random = new Random();

    @Inject
    private CoreBean coreBean;
    @Inject
    private AppointmentSingletonBean appointmentBean;
    @Inject
    private RegistrationSingletonBean registrationSingletonBean;
    @Inject
    private QueueDataSingletonBean queueDataBean;
    @Inject
    private ApplicationStateBean applicationState;
    @Inject
    private transient Logger logger;
    @Inject
    private Event<OpenDayEvent> openDayService;
    @Inject
    private Event<CloseDayEvent> closeDayService;
    @Inject
    private Event<VisitorEvent> visitorService;
    @Inject
    private Event<OneMinuteEvent> oneMinuteService;

    
    private boolean visitorEventFlag = false; //Флаг выставляется при любом событии, связанным с посетителем
    private Date lastCheckAppointmentDate; // Дата последнего вызова метода lastCheckAppointmentEvents

    @PostConstruct
    private void init() {
        logger.log(Level.INFO, "Init QueueSingletonBean ({0})", this);
        if (!applicationState.isMasterMode()) {
            Department currentDepartment = applicationState.getLocalDepartment();
            if (currentDepartment.getTimetableList() == null) {
                logger.log(Level.WARNING, "Отсутствует расписание для текущего департамента " + currentDepartment.getName() + " Запуск смены пока невозможен");
                return;
            }
            Schedule schedule = null;
            if (applicationState.getLocalDepartment().getCurrentTimetable() != null) {
                schedule = applicationState.getLocalDepartment().getCurrentTimetable().getCurrentSchedule();
            }
            if (schedule != null && schedule.isActive()) {
                logger.log(Level.INFO, "Start on loading");
                startDay(true);
            } else {
                closeDayService.fire(new CloseDayEvent());
            }
        }      
        Calendar currentCalendar = getInstance();
        oneMinuteService.fire(new OneMinuteEvent(currentCalendar, true));   // Проверка активности подразделений
    }

    @PreDestroy
    private void destroy() {
        logger.log(Level.INFO, "Destroy QueueSingletonBean ({0})", this);
    }

    @Lock(LockType.WRITE)
    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    @javax.ejb.Schedule(hour = "*", minute = "*/1", second = "0", persistent = false)
    public void executeTasks() {
        Calendar currentCalendar = getInstance();
        if (applicationState.isMasterMode() || applicationState.getLocalDepartment().getCurrentTimetable() == null) {
            return;
        }
        //   1)закрытие-открытие дня по временным интервалам
        // Запрашиваем текущий временной интервал
        Date nowDate = new Date();
        Schedule curentSchedule = applicationState.getLocalDepartment().getCurrentTimetable().getSchedule(nowDate);
        if (curentSchedule == null || !curentSchedule.isActive()) {
            // День должен быть закрыт
            if (applicationState.isOpenedDay()) {
                logger.log(Level.INFO, "Stop day by timer because current department schedule is {0}, current Date is {1}, department with id={2} schedule count is {3}", new Object[]{curentSchedule == null ? "null" : "not active", nowDate, applicationState.getLocalDepartment().getId(), applicationState.getLocalDepartment().getCurrentTimetable().getScheduleList().size()});
                stopDay();
            }
        } else {
            // День должен быть открыт
            if (!applicationState.isOpenedDay()) {
                logger.log(Level.INFO, "Start day by timer");
                startDay();
            }
        }
        if (applicationState.isOpenedDay()) {
            //   2)уведомление о необходимости вызвать или завершить посетителя по ПЗ
            checkAppointmentEvents(currentCalendar);
            //   3)вернуть отложенных на время посетителей в очередь из которых они были отложены
            returnHoldoverTimeVisitors(currentCalendar);
        }
    }

    @Lock(LockType.WRITE)
    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    @javax.ejb.Schedule(hour = "*", minute = "*", second = "*/1", persistent = false)
    public void executeSecondTasks() {
        if (visitorEventFlag && applicationState.isAppointmentFreeWorkplaceCall() && applicationState.isOpenedDay()) {
            checkAppointmentEvents(Calendar.getInstance());
            visitorEventFlag = false;
        }
    }

    /* Открытие и закрытие дня*/
    @Lock(LockType.WRITE)
    public void startDay() {
        startDay(false);
    }

    /**
     * вернуть отложенных на количество посетителей в очередь из которых они
     * были отложены после завершения в РО очередного посетителя
     *
     * @param visitorEvent
     */
    public void returnHoldoverCountVisitorToQueue(VisitorEvent visitorEvent) {
        Visitor visitor = visitorEvent.getVisitor();
        //Если завершили или отложили какого-нибудь посетителя на каком-нибудь рабочем окружении
        if (visitor != null && visitorEvent.getWorkplace() != null
                && (visitor.getCurrentStatus().isCanceledStatus() || visitor.getCurrentStatus().isWaitingStatus())) {
            Workplace activeWorkplace = this.getActiveWorkplace(visitorEvent.getWorkplace());
            if (activeWorkplace != null) {
                Iterator<Visitor> iter = activeWorkplace.getProcess().getAlgorithmBean().getHoldoverWorkplaceVisitorList().iterator();
                while (iter.hasNext()) {
                    Visitor activeHoldoverVisitor = iter.next();
                    if (activeHoldoverVisitor.getProcess().getDelayCounter() > 0) {
                        activeHoldoverVisitor.getProcess().setDelayCounter(activeHoldoverVisitor.getProcess().getDelayCounter() - 1);
                        if (activeHoldoverVisitor.getProcess().getDelayCounter() == 0) {
                            //Пора вернуть посетителя в очередь, из которой он блыл отложен
                            activeHoldoverVisitor.setNextService(activeHoldoverVisitor.getProcess().getDelayServiceFrom());
                            activeHoldoverVisitor.getProcess().setDelayCompleteTime(null);
                            activeHoldoverVisitor.setCurrentStatus(VisitorStatus.STEP_COMPLETE);
                        }
                    }
                }
            }
        }
    }

    /**
     * Перестроить все очереди
     *
     * @param visitorEvent
     * @throws AlgorithmTestException
     */
    public void updateWorkplaceQueues(VisitorEvent visitorEvent) throws AlgorithmTestException {
        visitorEventFlag = true;
        boolean testAppointment = visitorEvent.getTestAlgorithm() != null;
        Workplace testWorkplace = visitorEvent.getWorkplace();
        //Для ускорения сразу раскидаем посетителям по мапам
        //Мапа списков посетителей разбитая по услугам
        MultiValueMap<ServiceBase, Visitor> freeVisitorMultiValueMap = new MultiValueMap();
        //Мапа списков посетителей разбитая по рабочим окружениям - посетители в конкретное окно, но не по ПЗ и ЗНВ (например последний этап по живой очереди)
        MultiValueMap<Workplace, Visitor> workplaceVisitorMultiValueMap = new MultiValueMap();
        //Мапа списков посетителей разбитая по рабочим окружениям - посетители в конкретное окно по ПЗ и ЗНВ
        MultiValueMap<Workplace, Visitor> workplaceAppointmentVisitorMultiValueMap = new MultiValueMap();
        //Мапа списков посетителей разбитая по услугам - посетители в конкретное окно по ПЗ и ЗНВ
        //нужна для режима APPOINTMENT_FREE_WORKPLACE_CALL
        MultiValueMap<Service, Visitor> serviceAppointmentVisitorMultiKeyMap = new MultiValueMap();

        //Мапа списков отложенных посетителей по услугам
        MultiValueMap<ServiceBase, Visitor> holdoverVisitorMultiValueMap = new MultiValueMap();
        //Мапа списков отложенных посетителей по рабочим окружениям
        MultiValueMap<Workplace, Visitor> holdoverWorkplaceVisitorMultiValueMap = new MultiValueMap();
        //Раскидаем всех активных посетителям по этим мапам (дублирование по разным мапам не предусмотрено)
        Iterator<Visitor> iter = queueDataBean.getActiveVisitorList().iterator();
        while (iter.hasNext()) {
            Visitor activeVisitor = iter.next();
            //Вызванные нам не нужны
            if (activeVisitor.getCurrentStatus().isCalledStatus()) {
                continue;
            }
            if (activeVisitor.getCurrentStatus() != VisitorStatus.HOLDOVER) {
                if (activeVisitor.getCurrentWorkplace() == null) {
                    freeVisitorMultiValueMap.put(activeVisitor.getNextService(), activeVisitor);
                    //Учитываем теперь наследование - в окне может быть прописан родитель
                    if (activeVisitor.getNextService().getParent() != null) {
                        freeVisitorMultiValueMap.put(activeVisitor.getNextService().getParent(), activeVisitor);
                        if (activeVisitor.getNextService().getParent().getParent() != null) {
                            freeVisitorMultiValueMap.put(activeVisitor.getNextService().getParent().getParent(), activeVisitor);
                        }
                    }
                } else {
                    //Если включена опция вызова посетителей по ПЗ во всех окнах, то отображаем в списке посетителей по ПЗ с начала смены
                    if (activeVisitor.getCurrentServiceAppointment(applicationState.isAppointmentFreeWorkplaceCall() ?
                            applicationState.getLocalDepartment().getCurrentTimetable().getCurrentSchedule().getBeginDate(new Date()) : new Date()) == null) {
                        workplaceVisitorMultiValueMap.put(activeVisitor.getCurrentWorkplace(), activeVisitor);
                    } else {
                        workplaceAppointmentVisitorMultiValueMap.put(activeVisitor.getCurrentWorkplace(), activeVisitor);
                        if (applicationState.isAppointmentFreeWorkplaceCall()) {
                            serviceAppointmentVisitorMultiKeyMap.put(activeVisitor.getNextService(), activeVisitor);
                        }
                    }
                }
            } else {
                if (activeVisitor.getCurrentWorkplace() == null) {
                    holdoverVisitorMultiValueMap.put(activeVisitor.getNextService(), activeVisitor);
                    //Учитываем теперь наследование - в окне может быть прописан родитель
                    if (activeVisitor.getNextService().getParent() != null) {
                        holdoverVisitorMultiValueMap.put(activeVisitor.getNextService().getParent(), activeVisitor);
                        if (activeVisitor.getNextService().getParent().getParent() != null) {
                            holdoverVisitorMultiValueMap.put(activeVisitor.getNextService().getParent().getParent(), activeVisitor);
                        }
                    }

                } else {
                    holdoverWorkplaceVisitorMultiValueMap.put(activeVisitor.getCurrentWorkplace(), activeVisitor);
                }
            }
        }
        //Перебираем все рабочие окружения
        Iterator<Workplace> iterWorkplace = queueDataBean.getActiveWorkplaceList().iterator();
        LiveQueueDistributionType currentDistributionType = applicationState.getLiveQueueDistributionType();

        while (iterWorkplace.hasNext()) {
            Workplace workplace = iterWorkplace.next();
            if (testAppointment && (!workplace.equals(testWorkplace) || !workplace.getAlgorithm().equals(visitorEvent.getTestAlgorithm()))) {
                //Тестируем только нужный алгоритм
                continue;
            }           
            List<Visitor> freeVisitorList = new ArrayList<>();
            List<Visitor> workplaceVisitorList = new ArrayList<>();
            List<Visitor> appointmentVisitorList = new ArrayList<>();
            List<Visitor> holdoverVisitorList = new ArrayList<>();
            List<Visitor> holdoverWorkplaceVisitorList = new ArrayList<>();
            //Перебираем все услуги текущего РО и добавляем к спискам посетителей по этим услугам с учетом наследования
            for (WorkplaceService workplaceService : workplace.getWorkplaceServiceList()) {
                if (freeVisitorMultiValueMap.getCollection(workplaceService.getServiceBase()) != null) {
                    //С этими ребятами поинтересней
                    for (Visitor activeVisitor : freeVisitorMultiValueMap.getCollection(workplaceService.getServiceBase())) {
                        if (workplace.getType().equals(WorkplaceType.OPERATOR) || currentDistributionType.equals(LiveQueueDistributionType.DEFAULT)) {
                            freeVisitorList.add(activeVisitor);
                        } else if (currentDistributionType.equals(LiveQueueDistributionType.BY_ROOM)) {
                            if (activeVisitor.getDesiredRoom() == null || workplace == null || workplace.getRoom() == null || workplace.getRoom().equals(activeVisitor.getDesiredRoom())) {
                                freeVisitorList.add(activeVisitor);
                            }
                        } else if (currentDistributionType.equals(LiveQueueDistributionType.BY_WORKSTATION)) {
                            if (activeVisitor.getDesiredWorkplace() == null || workplace == null || workplace.equals(activeVisitor.getDesiredWorkplace())) {
                                freeVisitorList.add(activeVisitor);
                            }
                        }
                    }
                }
                if (holdoverVisitorMultiValueMap.getCollection(workplaceService.getServiceBase()) != null) {
                    holdoverVisitorList.addAll(holdoverVisitorMultiValueMap.getCollection(workplaceService.getServiceBase()));
                }
                //Если посетитель по ПЗ можно вызывать в любом свободном окне, оказывающем эту услугу (кроме режима чисто по живой очереди)
                if (applicationState.isAppointmentFreeWorkplaceCall()
                        && workplaceService.getMaintenanceMode() != MaintenanceMode.LIVE) {
                    for (Service service : serviceAppointmentVisitorMultiKeyMap.keySet()) {
                        if (service.isChildOrEqual(workplaceService.getServiceBase())) {
                            appointmentVisitorList.addAll(serviceAppointmentVisitorMultiKeyMap.getCollection(service));
                        }
                    }
                }
            }
            if (workplaceVisitorMultiValueMap.getCollection(workplace) != null) {
                workplaceVisitorList = (List<Visitor>) workplaceVisitorMultiValueMap.getCollection(workplace);
            }
            if (!applicationState.isAppointmentFreeWorkplaceCall() && workplaceAppointmentVisitorMultiValueMap.getCollection(workplace) != null) {
                appointmentVisitorList = (List<Visitor>) workplaceAppointmentVisitorMultiValueMap.getCollection(workplace);
            }
            if (holdoverWorkplaceVisitorMultiValueMap.getCollection(workplace) != null) {
                holdoverWorkplaceVisitorList = (List<Visitor>) holdoverWorkplaceVisitorMultiValueMap.getCollection(workplace);
            }

            holdoverVisitorList.addAll(fillGeneralHoldoverVisitorList(holdoverWorkplaceVisitorMultiValueMap, workplace));
            workplace.getProcess().getAlgorithmBean().updateQueue(freeVisitorList, workplaceVisitorList, appointmentVisitorList, holdoverVisitorList, holdoverWorkplaceVisitorList, testAppointment, applicationState.getMaxVisitorQueue(), applicationState.isDebugMode(), workplace.isActive());
        }
        if (testAppointment) {
            throw new AlgorithmTestException("Не найдено указанное активное окно, оказывающее услуги по заданному алгоритму", "", null);
        }

    }

    @Lock(LockType.WRITE)
    public void onDepartmentScheduleChangedEvent(@Observes DepartmentScheduleChangedEvent departmentScheduleChangedEvent) {
        Schedule schedule = departmentScheduleChangedEvent.getSchedule();
        if (applicationState.getLocalDepartment().getCurrentTimetable() != null && schedule.equals(applicationState.getLocalDepartment().getCurrentTimetable().getCurrentSchedule())) {
            if (!schedule.isActive()) {
                logger.log(Level.INFO, "Begin stopping day by {0}", departmentScheduleChangedEvent.getEmployee().getUsername());
                stopDay();
            } else {
                if (!applicationState.isOpenedDay()) {
                    logger.log(Level.INFO, "Begin starting day by {0}", departmentScheduleChangedEvent.getEmployee().getUsername());
                    startDay();
                }
            }
        }
    }


    @Asynchronous
    @AccessTimeout(-1)
    /**
     * Изменения произошл с алгоритмом постоения очереди
     *
     * @param criticalObjectChangeEvent
     */
    public void onChangeAlgorithmEvent(@Observes @EntityTypeQualifier(Algorithm.class) CriticalObjectChangeEvent criticalObjectChangeEvent) {
        Algorithm algorithm = (Algorithm)criticalObjectChangeEvent.getEntity();

        Iterator<Workplace> iterWorkplace = queueDataBean.getActiveWorkplaceList().iterator();
        while (iterWorkplace.hasNext()) {
            Workplace activeWorkplace = iterWorkplace.next();
            if (activeWorkplace.getAlgorithm().equals(algorithm) || activeWorkplace.equals(criticalObjectChangeEvent.getAdditionalInfo())) {
                activeWorkplace.setAlgorithm(algorithm);
                activeWorkplace.getProcess().getAlgorithmBean().updateAlgorithm(algorithm);
            }
        }
    }

    /**
     * События модуля из модуля ПЗ
     *
     * @param appointmentVisitorChangeEvent
     */
    @Asynchronous
    public void onAppointmentVisitorChangeEvent(@Observes AppointmentVisitorChangeEvent appointmentVisitorChangeEvent) {
        Visitor visitor = appointmentVisitorChangeEvent.getVisitor();
        switch (appointmentVisitorChangeEvent.getVisitorCommand()) {
            case UPDATE_IN_QUEUE:
                updateActiveVisitor(visitor, true);
                Employee currentEmployee = findEmployeeByWorkplace(visitor.getCurrentWorkplace());
                coreBean.logVisitorStatusChanges(visitor, currentEmployee);
                break;
            case REMOVE_FROM_QUEUE:
                removeActiveVisitorFromQueue(visitor);
                break;
        }
    }

    /**
     * Слушатель события подтверждения СМС-доставки
     *
     * @param smsDeliveryStatusEvent
     */
    @Lock(LockType.WRITE)
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public void onSmsDeliveryStatusEvent(@Observes SmsDeliveryStatusEvent smsDeliveryStatusEvent) {
        Map<String, SmsStatus> completedSmsStatusMap = new HashMap<>();
        for (SmsStatus smsStatus : smsDeliveryStatusEvent.getCompletedSmsStatusList()) {
            if (smsStatus.getEntityId() != null) {
                completedSmsStatusMap.put(smsStatus.getEntityId(), smsStatus);
            }
        }
        Iterator<Visitor> iter = queueDataBean.getActiveVisitorList().iterator();
        while (iter.hasNext()) {
            Visitor visitor = iter.next();
            if (completedSmsStatusMap.containsKey(visitor.getId())) {
                SmsStatus completeSmsStatus = completedSmsStatusMap.get(visitor.getId());
                visitor.setDeliveryStatus(completeSmsStatus.getDeliveryStatus());
                visitor = coreBean.saveEntity(visitor);
                updateActiveVisitor(visitor, true);
            }
        }
    }


    /**
     * Изменения произошл с каким-либо рабочим окружением (заблокировано, удалено, изменено...)
     *
     * @param criticalObjectChangeEvent
     */
    public void onChangeWorkplaceEvent(@Observes @EntityTypeQualifier(Workplace.class) CriticalObjectChangeEvent criticalObjectChangeEvent) {
        Workplace changedWorkplace = (Workplace) criticalObjectChangeEvent.getEntity();
        switch (criticalObjectChangeEvent.getChangeType()) {
            case REMOVE:
            case LOCK:
                Iterator<Workplace> iter = queueDataBean.getDayWorkplaceList().iterator();
                while (iter.hasNext()) {
                    Workplace workplaceNext = iter.next();
                    if (workplaceNext.equals(changedWorkplace)) {
                        if (workplaceNext.isActive()) {
                            unregisterWorkplace(workplaceNext);
                        }
                        queueDataBean.getDayWorkplaceList().remove(workplaceNext);
                        break;
                    }
                }
                break;
            case UNLOCK:
            case CHANGE:
            case ADD:
                changedWorkplace = queueDataBean.updateDayWorkplace(changedWorkplace, criticalObjectChangeEvent.getChangeType() == ChangeType.CHANGE);
                try {
                    updateWorkplaceQueues(new VisitorEvent(changedWorkplace));
                } catch (AlgorithmTestException ex) {
                    getLogger(QueueSingletonBean.class.getName()).log(Level.SEVERE, ex.getMessage());
                }
                break;
        }
    }


    //Протестировать текущий алгоритм
    public void testAlgorithm(Algorithm algorithm, Workplace activeWorkplace) throws AlgorithmTestException {
        updateWorkplaceQueues(new VisitorEvent(activeWorkplace, algorithm));
    }

    public void startDay(boolean onStartup) {
        if (applicationState.isOpenedDay() || applicationState.isMasterMode()) {
            return;
        }
        TimetableDepartment currentTimetable = applicationState.getLocalDepartment().getCurrentTimetable();
        Schedule currentSchedule = currentTimetable.getCurrentSchedule();
        boolean aroundTheClock = currentTimetable.isAroundTheClock();
        if (currentSchedule != null) {
            registrationSingletonBean.reinitCurrentDepartment();
            //Заполним список активных посетителей на текущуюу смену
            queueDataBean.fillActiveVisitorList(aroundTheClock ? null : currentSchedule);
            logger.info("Adding and init workplace list");
            for (Workplace workplace : coreBean.getWorkplaceList()) {
                this.addDayAndInitWorkplace(workplace);
            }

            calculateServedCount();
            //Восстановление состояния после перезагрузки сервера во время рабочего дня - часть посетителей может быть вызвана но не завершена
            List<Visitor> calledVisitorList = new ArrayList<>();
            Iterator<Workplace> iterWorkplace = queueDataBean.getActiveWorkplaceList().iterator();
            while (iterWorkplace.hasNext()) {
                Workplace activeWorkplace = iterWorkplace.next();
                if (activeWorkplace.getProcess().getCurrentVisitor() != null) {
                    calledVisitorList.add(activeWorkplace.getProcess().getCurrentVisitor());
                }
            }
            Iterator<Visitor> iterVisitor = queueDataBean.getActiveVisitorList().iterator();
            while (iterVisitor.hasNext()) {
                Visitor activeVisitor = iterVisitor.next();
                //Если у посетителя статус вызван, но он не прикрепился к автоматически вызванной станции - с этим надо что-то делать
                if (!calledVisitorList.contains(activeVisitor) && activeVisitor.getCurrentStatus().isCalledStatus()) {
                    if (activeVisitor.getNextService().getServiceHoldover(applicationState.getLocalDepartment()) != null) {
                        holdoverVisitor(activeVisitor, Visitor.VISITOR_RESTORE_COMMENT, 0, 0);
                    } else {
                        completeReceptionVisitor(activeVisitor);
                    }
                }
            }
            //Сгенерируем водяной знак для текущей смены           
            if(!aroundTheClock) {
                generateWaterMark(currentSchedule);
            }
            if (!queueDataBean.getDayWorkplaceList().isEmpty()) {
                visitorService.fire(new VisitorEvent(null, queueDataBean.getDayWorkplaceList().get(0), "", null));
            }
            openDayService.fire(new OpenDayEvent(onStartup));
        }
    }

    @Lock(LockType.WRITE)
    public void stopDay() {
        if (applicationState.isMasterMode()) {
            return;
        }
        if (applicationState.isOpenedDay()) {
            try {
                queueDataBean.getDayWorkplaceList().clear(); // Очистка активных рабочих окружений
                queueDataBean.getActiveVisitorList().clear(); // Очистка спика активных посетителей
                // очистка очереди
                registrationSingletonBean.removeAppointmentAndVisitors();
                registrationSingletonBean.reinitCurrentDepartment();
                closeDayService.fire(new CloseDayEvent());
            } catch (Exception ex) {
                logger.log(Level.WARNING, "STOP DAY: failed");
                logger.log(Level.SEVERE, null, ex);
            }
        }
    }

    /*
     * 
     */
    @Lock(LockType.WRITE)
    public Workplace registerEmployee(Employee employee, Workplace workplace) {
        //Если рабочее окружение было до этого зарегистрировано - освобождаем
        unregisterWorkplace(workplace);
        //Если этот же пользователь зарегистрирован на другой машине, то его надо там разлогинить
        Workplace existedEmployeeActiveWorkplace = null;
        for (Workplace activeWorkplace : queueDataBean.getActiveWorkplaceList()) {
            if (activeWorkplace.getProcess().getCurrentEmployee()!=null && employee.equals(activeWorkplace.getProcess().getCurrentEmployee())) {
                existedEmployeeActiveWorkplace = activeWorkplace;
            }
        }
        if (existedEmployeeActiveWorkplace != null) {
            logger.log(Level.WARNING, "Сессия пользователя {0} завершена в РО {1}, т.к. он зашел в РО {2}", new Object[]{employee.getFullName(), existedEmployeeActiveWorkplace.getName(), workplace.getName()});
            unregisterWorkplace(existedEmployeeActiveWorkplace);

        }

        workplace.getProcess().setCurrentEmployee(employee);
        // определим текущего посетителя - восттановление состояния
        Iterator<Visitor> iter = queueDataBean.getActiveVisitorList().iterator();
        while (iter.hasNext()) {
            Visitor visitor = iter.next();
            if (workplace.equals(visitor.getCurrentWorkplace()) && visitor.getCurrentStatus().isCalledStatus()) {
                //Если нет текущего посетителя или есть и он вызван раньше выбранного, то заменяем на выбранного
                if (workplace.getProcess().getCurrentVisitor() == null || workplace.getProcess().getCurrentVisitor().getCallDate().before(visitor.getCallDate())) {
                    workplace.getProcess().setCurrentVisitor(visitor);
                }
            }
        }
        //Создадим и обновим очередь к окну
        AlgorithmBean algorithmBean = new AlgorithmBean(workplace.getAlgorithm(), workplace);
        workplace.getProcess().setAlgorithmBean(algorithmBean);
        workplace.getProcess().setChangingStatusTime(new Date());
        //Удаляем старый, добавляем новый
        queueDataBean.updateDayWorkplace(workplace, false);
        fireWorkplaceVisitorEvent(workplace);

        return workplace;
    }

    public void fireWorkplaceVisitorEvent(Workplace workplace) {
        if (workplace.getProcess().getCurrentVisitor() != null) {
            visitorService.fire(new VisitorEvent(workplace.getProcess().getCurrentVisitor(), workplace, workplace.getProcess().getCurrentVisitor().getTicketNumber(), this.getCurrentEmployee(workplace)));
        } else {
            visitorService.fire(new VisitorEvent(null, workplace, "", null));
        }


    }

    //деактивация рабочего окружения
    @Lock(LockType.WRITE)
    public void unregisterWorkplace(Workplace workplace) {
        workplace.getProcess().setChangingStatusTime(new Date());
        if (queueDataBean.getDayWorkplaceList().contains(workplace)) {
            AlgorithmBean algorithmBean = new AlgorithmBean(workplace.getAlgorithm(), workplace);
            workplace.getProcess().setAlgorithmBean(algorithmBean);
            workplace.getProcess().setCurrentEmployee(null);
            queueDataBean.updateDayWorkplace(workplace, false);
            visitorService.fire(new VisitorEvent(null, workplace, "", null));
        }
    }

    /*Работа с поситетелями - регистрация, вызов, откладывание, перенаправление*/
    @Lock(LockType.WRITE)
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public Visitor registerVisitor(Visitor visitor) throws MaxNumberVisitorsException, LiveQueueNotAllowedException {
        boolean liveQueueEnable = false;
        Workplace desiredWorkplace = null;
        Date nowDate = new Date();
        out:
        switch (applicationState.getLiveQueueDistributionType()) {
            case DEFAULT: {
                Iterator<Workplace> iterator = queueDataBean.getCurrentScheduleWorkplaceList().iterator();
                while (iterator.hasNext()) {
                    Workplace selectedWorkplace = iterator.next();
                    //Если расписание окна не активно в текущий момент
                    if (selectedWorkplace.getCurrentSchedule() == null) {
                        continue;
                    }
                    //Выбираем рабочее окружение для запуска
                    for (WorkplaceService workplaceService : selectedWorkplace.getWorkplaceServiceList()) {
                        if (visitor.getNextService().isChildOrEqual(workplaceService.getServiceBase()) && selectedWorkplace.getTimetableInDepartment(nowDate) != null
                                && workplaceService.getMaintenanceMode() != MaintenanceMode.APPOINTMENT) {
                            liveQueueEnable = true;
                            break out;
                        }
                    }
                }
                break;
            }
            case BY_ROOM: {
                Room desiredRoom = queueDataBean.findDesiredObject(visitor.getNextService(), Room.class, true);
                visitor.setDesiredRoom(desiredRoom);
                liveQueueEnable = true;
                break;
            }
            case BY_WORKSTATION: {
                desiredWorkplace = queueDataBean.findDesiredObject(visitor.getNextService(), Workplace.class, true);
                if (desiredWorkplace != null) {
                    visitor.setDesiredWorkplace(desiredWorkplace);
                }
                liveQueueEnable = true;
            }
        }
        if (!liveQueueEnable) {
            throw new LiveQueueNotAllowedException();
        }

        if (visitor.getTicketNumber() == null) {
            registrationSingletonBean.visitorFillTicketNumber(visitor.getNextService(), visitor);
        }
        visitor.setCurrentStatus(VisitorStatus.SIGNUP);
        visitor = coreBean.saveEntity(visitor);
        int[] result = calculateCountBefore(visitor, desiredWorkplace);
        visitor.getProcess().setCountBefore(result[0]);
        visitor.getProcess().setTimeMinuteBefore(result[1]);
        updateActiveVisitor(visitor, true);
        logger.log(Level.INFO, "Register visitor {0} with ticketNumber {1} for {2} ",
                new Object[]{visitor, visitor.getTicketNumber(), visitor.getNextService().getName()});

        // логируем операцию выдачи талона
        coreBean.logVisitorStatusChanges(visitor, null);
        return visitor;
    }

    @Lock(LockType.WRITE)
    public Visitor callNextVisitor(Workplace workplace) {
        Visitor oldVisitor = getCurrentVisitorByWorkplace(workplace);
        if (oldVisitor != null) {
            completeReceptionVisitor(oldVisitor);
        }
        Workplace activeWorkplace = this.getActiveWorkplace(workplace);
        if (activeWorkplace != null && !activeWorkplace.getProcess().getAlgorithmBean().getVirtualVisitorQueue().isEmpty()) {
            Visitor visitor = activeWorkplace.getProcess().getAlgorithmBean().getNextVisitorForCall();
            if (visitor != null) {
                return callNextVisitor(workplace, visitor, VisitorStatus.CALL_NEXT);

            }
        }
        return null;
    }

    @Lock(LockType.WRITE)
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public Visitor callNextVisitor(Workplace workplace, Visitor visitor, VisitorStatus visitorStatus) {
        String visitorStr = visitor != null ? visitor.toString() : "null";
        visitor = findActiveVisitor(visitor);
        if (visitor == null) {
            logger.log(Level.INFO, "callNextVisitor: Visitor {0} is empty", visitorStr);
            return null;
        }
        visitor.setCurrentStatus(visitorStatus);
        visitor.setCallDate(new Date());

        logger.log(Level.INFO, "Call next visitor {0} with ticketNumber {1} ({2}) for {3} on {4}",
                new Object[]{visitor, visitor.getTicketNumber(), visitorStatus, visitor.getNextService().getName(), workplace.getName()});
        visitor.setCurrentWorkplace(workplace);
        visitor = coreBean.saveEntity(visitor);
        updateActiveVisitor(visitor, false);

        // запомним текущего на рабочем месте
        setCurrentVisitorForWorkplace(workplace, visitor);

        Employee currentEmployee = getCurrentEmployee(workplace);
        coreBean.logVisitorStatusChanges(visitor, currentEmployee);
        visitorService.fire(new VisitorEvent(visitor, visitor.getCurrentWorkplace(), visitor.getTicketNumber(), this.getCurrentEmployee(visitor.getCurrentWorkplace())));
        coreBean.getEm().flush();
        return visitor;
    }

    @Lock(LockType.WRITE)
    public Visitor callVisitor(Workplace workplace, Visitor visitor) {
        String visitorStr = visitor != null ? visitor.toString() : "null";
        visitor = findActiveVisitor(visitor);
        if (visitor == null) {
            logger.log(Level.INFO, "callVisitor manual: Visitor {0} is empty", visitorStr);
            return null;
        }
        // если он уже где-то вызван - возвращаем пусто
        if (visitor.getCurrentStatus().isCalledStatus()) {
            return null;
        }

        Visitor oldVisitor = getCurrentVisitorByWorkplace(workplace);
        if (oldVisitor != null) {
            if (oldVisitor.getNextService().getServiceHoldover(applicationState.getLocalDepartment()) != null) {
                holdoverVisitor(oldVisitor, "", 0, 0); //SUOGIBDD-220
            } else {
                completeReceptionVisitor(oldVisitor);
            }
        }

        visitor.setCurrentWorkplace(workplace);
        visitor.setCurrentStatus(VisitorStatus.CALL_JUMP);
        visitor.setCallDate(new Date());
        logger.log(Level.INFO, "Call manual {0} with ticketNumber {1} for {2} on {3}",
                new Object[]{visitor, visitor.getTicketNumber(), visitor.getNextService().getName(), workplace.getName()});
        visitor = coreBean.saveEntity(visitor);
        updateActiveVisitor(visitor, false);

        // запомним текущего на рабочем месте
        setCurrentVisitorForWorkplace(workplace, visitor);
        Employee currentEmployee = getCurrentEmployee(workplace);
        coreBean.logVisitorStatusChanges(
                visitor, currentEmployee);
        visitorService.fire(new VisitorEvent(visitor, visitor.getCurrentWorkplace(), visitor.getTicketNumber(), getCurrentEmployee(visitor.getCurrentWorkplace())));

        return visitor;
    }

    public void setVisitoArrivedStatus(Visitor arrivedVisitor) {
        String visitorStr = arrivedVisitor != null ? arrivedVisitor.toString() : "null";
        Visitor visitor = findActiveVisitor(arrivedVisitor);
        if (visitor == null) {
            logger.log(Level.INFO, "setVisitoArrivedStatus: Visitor {0} is empty", visitorStr);
            return;
        }
        visitor.getProcess().setArrived(true);
        visitor.getProcess().setSuspended(false);
        Employee currentEmployee = getCurrentEmployee(visitor.getCurrentWorkplace());
        visitorService.fire(new VisitorEvent(visitor, visitor.getCurrentWorkplace(), visitor.getTicketNumber(), getCurrentEmployee(visitor.getCurrentWorkplace()), true));
        coreBean.logVisitorStatusChanges(visitor, currentEmployee);
    }

    public void setVisitorSuspendedStatus(Visitor stoppedVisitor) {
        String visitorStr = stoppedVisitor != null ? stoppedVisitor.toString() : "null";
        Visitor visitor = findActiveVisitor(stoppedVisitor);
        if (visitor == null) {
            logger.log(Level.INFO, "setVisitoArrivedStatus: Visitor {0} is empty", visitorStr);
            return;
        }
        visitor.getProcess().setArrived(false);
        visitor.getProcess().setSuspended(true);
        Employee currentEmployee = getCurrentEmployee(visitor.getCurrentWorkplace());
        visitorService.fire(new VisitorEvent(visitor, visitor.getCurrentWorkplace(), "", getCurrentEmployee(visitor.getCurrentWorkplace())));
        coreBean.logVisitorStatusChanges(visitor, currentEmployee);
    }

    @Lock(LockType.WRITE)
    public void completeReceptionVisitor(Visitor completeVisitor) {
        String visitorStr = completeVisitor != null ? completeVisitor.toString() : "null";
        Visitor visitor = findActiveVisitor(completeVisitor);
        if (visitor == null) {
            logger.log(Level.INFO, "completeReceptionVisitor: Visitor {0} is empty", visitorStr);
            return;
        }
        Service currentService = visitor.getNextService();
        logger.log(Level.INFO, "Complete {0} with ticketNumber {1} for {2} on {3}",
                new Object[]{visitor, visitor.getTicketNumber(), visitor.getNextService() == null ? "null" : visitor.getNextService().getName(),
                        visitor.getCurrentWorkplace() == null ? "null" : visitor.getCurrentWorkplace() == null ? "null"
                                : visitor.getCurrentWorkplace().getName() + " (" + visitor.getCurrentWorkplace().getName() + ")"}
        );
        // сначала поищем в этапах - есть ли следующая по этапу услуга и предзапись, соответствующая ей.
        Appointment nextAppointment = null;
        boolean isNextAppointment = false;
        for (Appointment appointment : visitor.getOrderedAppointments()) {
            if (isNextAppointment) {
                nextAppointment = appointment;
                break;
            }
            if (appointment.getService().equals(visitor.getNextService())) {
                isNextAppointment = true;
            }
        }

        if (visitor.getCurrentWorkplace() != null) {
            setCurrentVisitorForWorkplace(visitor.getCurrentWorkplace(), null);
        }
        updateWorkplaceServedCount(visitor.getCurrentWorkplace(), visitor.getNextService());
        // если нашли следующий этап предзаписи
        if (nextAppointment != null) {
            Employee currentEmployee = getCurrentEmployee(visitor.getCurrentWorkplace());
            visitor.setCurrentStatus(VisitorStatus.STEP_COMPLETE);
            visitorService.fire(new VisitorEvent(visitor, visitor.getCurrentWorkplace(), "", getCurrentEmployee(visitor.getCurrentWorkplace())));
            coreBean.logVisitorStatusChanges(visitor, currentEmployee);
            // сбросим список отложенных
            visitor.setCompleteDate(new Date());
            visitor.setNextService(nextAppointment.getService());
            if (nextAppointment.getWorkplace() != null) {
                if (nextAppointment.getDatetimeFrom() == null
                        && nextAppointment.getWorkplace().isBlocked()) {
                    visitor.setCurrentWorkplace(null);
                } else {
                    visitor.setCurrentWorkplace(nextAppointment.getWorkplace());
                }
                visitor = coreBean.saveEntity(visitor);
                updateActiveVisitor(visitor, true);
            } else {
                visitor.setCurrentWorkplace(null);
                visitor = coreBean.saveEntity(visitor);
                queueDataBean.getActiveVisitorList().remove(visitor);
            }
        } else {          
            if (currentService.getAutoRedirectService(applicationState.getLocalDepartment()) != null) {
                try {
                    Employee currentEmployee = getCurrentEmployee(visitor.getCurrentWorkplace());
                    visitor.setCurrentStatus(VisitorStatus.STEP_COMPLETE);
                    visitorService.fire(new VisitorEvent(visitor, visitor.getCurrentWorkplace(), "", getCurrentEmployee(visitor.getCurrentWorkplace())));
                    coreBean.logVisitorStatusChanges(visitor, currentEmployee);
                    // сбросим список отложенных
                    visitor.setCompleteDate(new Date());
                    moveVisitorToService(visitor, (Service) currentService.getAutoRedirectService(applicationState.getLocalDepartment()).getServiceTo());
                } catch (MaxNumberVisitorsException ex) {
                    logger.log(Level.SEVERE, ex.getMessage(), ex);
                }
            } else {
                Employee currentEmployee = getCurrentEmployee(visitor.getCurrentWorkplace());
                visitor.setCurrentStatus(VisitorStatus.COMPLETE);
                visitorService.fire(new VisitorEvent(visitor, visitor.getCurrentWorkplace(), "", getCurrentEmployee(visitor.getCurrentWorkplace())));
                coreBean.logVisitorStatusChanges(visitor, currentEmployee);
                // сбросим список отложенных
                visitor.setCompleteDate(new Date());
                visitor.setCurrentWorkplace(null);
                visitor = coreBean.saveEntity(visitor);
                queueDataBean.getActiveVisitorList().remove(visitor);
            }
        }
        coreBean.getEm().flush();
    }

    @Lock(LockType.WRITE)
    public Visitor cancelVisitor(Workplace workplace, Visitor visitor, VisitorStatus cancelStatus) {
        String visitorStr = visitor != null ? visitor.toString() : "null";
        visitor = findActiveVisitor(visitor);
        if (visitor == null) {
            logger.log(Level.INFO, "cancelVisitor: Visitor {0} is empty", visitorStr);
            return null;
        }
        // сбросим список отложенных
        if (workplace != null && visitor.getCurrentStatus().isCalledStatus()) {
            if (cancelStatus == VisitorStatus.CANCELED_CONSULTATED) {
                updateWorkplaceServedCount(visitor.getCurrentWorkplace(), visitor.getNextService());
            }
        }
        visitor.setCurrentStatus(cancelStatus);
        if (workplace != null) {
            visitorService.fire(new VisitorEvent(visitor, visitor.getCurrentWorkplace(), "", getCurrentEmployee(visitor.getCurrentWorkplace())));
        }

        visitor.setCompleteDate(new Date());
        //Найдем текущего посетителя на рабочем месте и если он совпадает с отменяемым - освобождаем рабочее место
        Visitor currentWorkplaceVisitor = getCurrentVisitorByWorkplace(visitor.getCurrentWorkplace());
        if (currentWorkplaceVisitor != null && currentWorkplaceVisitor.equals(visitor)) {
            setCurrentVisitorForWorkplace(visitor.getCurrentWorkplace(), null);
        }

        Employee currentEmployee = null;

        if (workplace != null) {
            setCurrentVisitorForWorkplace(workplace, null);
            currentEmployee = getCurrentEmployee(workplace);
            if (currentEmployee != null) {
                logger.log(Level.INFO, "Cancel {0} with ticketNumber {1} for {2} on {3} by {4}", new Object[]{visitor, visitor.getTicketNumber(), visitor.getNextService().getName(), workplace.getName(), currentEmployee.getFullName()});
            } else {
                logger.log(Level.INFO, "Cancel {0} with ticketNumber {1} for {2} on {3}", new Object[]{visitor, visitor.getTicketNumber(), visitor.getNextService().getName(), workplace.getName()});
            }
        } else {
            logger.log(Level.INFO, "Cancel {0} with ticketNumber {1} for {2} ", new Object[]{visitor, visitor.getTicketNumber(), visitor.getNextService().getName()});
        }
        visitor = coreBean.saveEntity(visitor);
        queueDataBean.getActiveVisitorList().remove(visitor);

        coreBean.logVisitorStatusChanges(visitor, currentEmployee);

        Appointment parentAppointment = visitor.getParentAppointment();
        if (parentAppointment != null) {
            registrationSingletonBean.removeParentAppointment(parentAppointment);
        }

        return visitor;
    }

    @Lock(LockType.WRITE)
    public Visitor moveVisitorToWorkplace(Visitor visitor, Workplace workplace) {
        String visitorStr = visitor != null ? visitor.toString() : "null";
        visitor = findActiveVisitor(visitor);
        if (visitor == null) {
            logger.log(Level.INFO, "moveVisitorToWorkplace: Visitor {0} is empty", visitorStr);
            return null;
        }
        Workplace oldWorkplace = visitor.getCurrentWorkplace();
        logger.log(Level.INFO, "Move {0} with ticketNumber {1} for {2} from {3} to {4}",
                new Object[]{visitor, visitor.getTicketNumber(), visitor.getNextService().getName(),
                        visitor.getCurrentWorkplace().getName(), workplace.getName()}
        );

        Employee currentEmployee = getCurrentEmployee(visitor.getCurrentWorkplace());
        visitor.setCompleteDate(new Date());
        // прописываем статус (перенаправлен)
        visitor.setCurrentStatus(VisitorStatus.MOVE_TO_WORKPLACE);
        // отмечаем что в рабочем окружении обслужили посетителя
        coreBean.logVisitorStatusChanges(visitor, currentEmployee);
        // погасим номер на табло оператора старого окна
        visitorService.fire(new VisitorEvent(visitor, oldWorkplace, "", getCurrentEmployee(oldWorkplace)));
        // и поставили его в список отложенных в новом рабочем окружении
        //отмечаем, что на старом рабочем окружении никто не вызван
        setCurrentVisitorForWorkplace(visitor.getCurrentWorkplace(), null);
        //повторный вызов немедленно (после освобождения оператора)
        // прописываем новое рабочее окружение
        visitor.setCurrentWorkplace(workplace);
        visitor.setDesiredWorkplace(null);
        visitor.setDesiredRoom(null);
        visitor = coreBean.saveEntity(visitor);
        updateActiveVisitor(visitor, true);

        return visitor;
    }

    @Lock(LockType.WRITE)
    public Visitor moveVisitorToService(Visitor visitor, Service newService) throws MaxNumberVisitorsException {
        String visitorStr = visitor != null ? visitor.toString() : "null";
        visitor = findActiveVisitor(visitor);
        if (visitor == null) {
            logger.log(Level.INFO, "moveVisitorToService: Visitor {0} is empty", visitorStr);
            return null;
        }
        Workplace oldWorkplace = visitor.getCurrentWorkplace();
        logger.log(Level.INFO, "Move {0}  with ticketNumber {1} for {2} from {3} on {4}",
                new Object[]{visitor, visitor.getTicketNumber(), visitor.getNextService().getName(),
                        (visitor.getCurrentWorkplace() != null ? visitor.getCurrentWorkplace().getName() : null),
                        newService.getName()}
        );
        //отмечаем, что на старом рабочем окружении никто не вызван
        Employee currentEmployee = null;
        if (visitor.getCurrentWorkplace() != null) {
            setCurrentVisitorForWorkplace(visitor.getCurrentWorkplace(), null);
            currentEmployee = getCurrentEmployee(visitor.getCurrentWorkplace());
        }
        visitor.setCompleteDate(new Date());
        visitor.setCurrentStatus(VisitorStatus.MOVE_TO_SERVICE);
        // отмечаем что в рабочем окружении обслужили посетителя
        coreBean.logVisitorStatusChanges(visitor, currentEmployee);
        // и поставили его в список по новой услуге
        visitor.setNextService(newService);
        visitor.setCompleteDate(new Date());
        visitor.setCurrentWorkplace(null);
        // погасим номер на табло оператора старого окна
        visitorService.fire(new VisitorEvent(visitor, oldWorkplace, "", getCurrentEmployee(oldWorkplace)));

        visitor.setDesiredWorkplace(null);
        visitor.setDesiredRoom(null);
        visitor = coreBean.saveEntity(visitor);
        updateActiveVisitor(visitor, true);
        return visitor;

    }

    @Lock(LockType.WRITE)
    public Visitor holdoverVisitor(Visitor visitor, String reason, int delayTime, int delayCount) {
        String visitorStr = visitor != null ? visitor.toString() : "null";
        visitor = findActiveVisitor(visitor);
        if (visitor == null) {
            logger.log(Level.INFO, "holdoverVisitor: Visitor {0} is empty", visitorStr);
            return null;
        }
        // определим, обслуживает ли текущее рабочее окружение услугу, в которую мы откладываем посетителя
        boolean currentWorkplaceProvidedService = false;
        for (WorkplaceService ws : visitor.getCurrentWorkplace().getWorkplaceServiceList()) {
            if (visitor.getNextService().getServiceHoldover(applicationState.getLocalDepartment()).getServiceTo().isChildOrEqual(ws.getServiceBase())) {
                currentWorkplaceProvidedService = true;
                break;
            }
        }
        //Освободим слоты времени на последующие этапы, если посетитель по ПЗ
        appointmentBean.clearNextTimeSlots(visitor);

        visitor.setCurrentStatus(VisitorStatus.HOLDOVER);
        //visitor.setCallDate(addNMinuteToDate(new Date()));

        try {
            logger.log(Level.INFO, "Holdover {0} with ticketNumber {1} from {2} to {3}",
                    new Object[]{visitor, visitor.getTicketNumber(), visitor.getNextService().getName(),
                            visitor.getNextService().getServiceHoldover(applicationState.getLocalDepartment()).getServiceTo().getName()}
            );
        } catch (Exception e) {
            logger.log(Level.WARNING, e.getMessage());
            return visitor;
        }
        if (reason != null && !reason.isEmpty()) {
            visitor.setComment(reason);
        }

        Workplace workplace = visitor.getCurrentWorkplace();
        setCurrentVisitorForWorkplace(workplace, null);
        Employee currentEmployee = getCurrentEmployee(workplace);
        coreBean.logVisitorStatusChanges(visitor, currentEmployee);
        Service currentService = visitor.getNextService();
        visitor.setNextService((Service) visitor.getNextService().getServiceHoldover(applicationState.getLocalDepartment()).getServiceTo());
        // если не обслуживает - то предоставим право всем прочим рабочим окружениям обслужить отложенного посетителя
        if (!currentWorkplaceProvidedService) {
            visitor.setCurrentWorkplace(null);
        }
        visitor = coreBean.saveEntity(visitor);
        //Утанавливаем транзиентные поля для активного посетителя
        visitor.getProcess().setDelayServiceFrom(null);
        visitor.getProcess().setDelayCounter(0);
        visitor.getProcess().setDelayCompleteTime(null);

        if (delayCount != 0 || delayTime != 0) {
            visitor.getProcess().setDelayServiceFrom(currentService);
            if (delayTime != 0) {
                visitor.getProcess().setDelayCompleteTime(addNMinuteToDate(new Date(), delayTime));
            } else {
                visitor.getProcess().setDelayCounter(delayCount);
            }
        }
        updateActiveVisitor(visitor, false);
//        coreBean.logVisitorStatusChanges(visitor, OperationType.HOLDOVER, currentEmployee);
        visitorService.fire(new VisitorEvent(visitor, workplace, "", getCurrentEmployee(workplace)));

        return visitor;
    }

    /**
     * @param visitor
     * @param fireVisitorEvent послать событие на обновление очереди (workplace =
     *                         null). Если нужно обновить табло, то событие посылается вручную
     */
    public void updateActiveVisitor(Visitor visitor, boolean fireVisitorEvent) {
        //На мастере реальных очередей нет
        if (applicationState.isMasterMode()) {
            return;
        }
        Schedule currentSchedule = applicationState.getLocalDepartment().getCurrentTimetable().getCurrentSchedule();
        if (currentSchedule != null) {
            Date nowDate = new Date();
            Date scheduleDateFrom = currentSchedule.getBeginDate(nowDate);
            Date scheduleDateTo = currentSchedule.getEndDate(nowDate);

            queueDataBean.getActiveVisitorList().remove(visitor);

            if (visitor.getCurrentStatus() != VisitorStatus.COMPLETE
                    && visitor.getTicketNumber() != null && (!visitor.isAppointmentVisitor()
                    || (scheduleDateFrom.before(visitor.getParentAppointment().getDatetimeFrom()) && scheduleDateTo.after(visitor.getParentAppointment().getDatetimeFrom())))) {
                queueDataBean.getActiveVisitorList().add(visitor);
                if (fireVisitorEvent) {
                    visitorService.fire(new VisitorEvent(visitor, null, visitor.getTicketNumber(), null));
                }
            }
        }
    }

    /**
     * Удалить активного посетителя из очереди
     *
     * @param visitor
     */
    public void removeActiveVisitorFromQueue(Visitor visitor) {
        Iterator<Workplace> iter = queueDataBean.getDayWorkplaceList().iterator();
        while (iter.hasNext()) {
            Workplace activeWorkplace = iter.next();
            Visitor currentVisitor = activeWorkplace.getProcess().getCurrentVisitor();
            if (currentVisitor != null && currentVisitor.equals(visitor)) {
                activeWorkplace.getProcess().setCurrentVisitor(null);
            }
        }
        queueDataBean.getActiveVisitorList().remove(visitor);
        if (visitor.getCurrentWorkplace() != null) {
            try {
                updateWorkplaceQueues(new VisitorEvent(visitor.getCurrentWorkplace()));
            } catch (AlgorithmTestException algorithmTestException) {
            }
        }
        //Если посетитель где-то вызван, то надо погасить ЦТ
        if (visitor.getCurrentWorkplace() != null) {
            visitorService.fire(new VisitorEvent(visitor, visitor.getCurrentWorkplace(), "", null));
        }

    }

    /**
     * Получить ссылку на активное рабочее окружение
     *
     * @param workplace
     * @return
     */
    public Workplace getActiveWorkplace(Workplace workplace) {
        Iterator<Workplace> iter = queueDataBean.getActiveWorkplaceList().iterator();
        while (iter.hasNext()) {
            Workplace activeWorkplace = iter.next();
            if (activeWorkplace.equals(workplace)) {
                return activeWorkplace;
            }
        }
        return null;
    }

    public Employee findEmployeeByWorkplace(Workplace workplace) {
        Iterator<Workplace> iter = queueDataBean.getActiveWorkplaceList().iterator();
        while (iter.hasNext()) {
            Workplace activeWorkplace = iter.next();
            if (activeWorkplace.equals(workplace)) {
                return activeWorkplace.getProcess().getCurrentEmployee();
            }
        }
        return null;
    }

    //Найти окружение, которое используется на данной рабочей станции
    public Workplace findActiveWorkplaceByWorkplace(Workplace workplace) {
        Iterator<Workplace> iter = queueDataBean.getActiveWorkplaceList().iterator();
        while (iter.hasNext()) {
            Workplace activeWorkplace = iter.next();
            if (workplace.equals(activeWorkplace)) {
                return activeWorkplace;
            }
        }
        return null;
    }

    public Workplace findActiveWorkplaceByEmployeeSessionid(String sessionid) {
        Iterator<Workplace> iter = queueDataBean.getActiveWorkplaceList().iterator();
        while (iter.hasNext()) {
            Workplace activeWorkplace = iter.next();
            if (activeWorkplace.getProcess().getCurrentEmployee() != null && sessionid.equals(activeWorkplace.getProcess().getCurrentEmployee().getSessionid())) {
                return activeWorkplace;
            }
        }
        return null;
    }

    public Visitor getCurrentVisitorByWorkplace(Workplace workplace) {
        if (workplace == null) {
            return null;
        }
        Iterator<Workplace> iter = queueDataBean.getActiveWorkplaceList().iterator();
        while (iter.hasNext()) {
            Workplace activeWorkplace = iter.next();
            if (activeWorkplace.equals(workplace)) {
                return activeWorkplace.getProcess().getCurrentVisitor();
            }
        }
        return null;
    }

    public void setCurrentVisitorForWorkplace(Workplace workplace, Visitor visitor) {
        if (workplace == null) {
            logger.log(Level.WARNING, "setCurrentVisitorForWorkplace: workplace is null for {0}", visitor);
            return;
        }
        Iterator<Workplace> iter = queueDataBean.getDayWorkplaceList().iterator();
        while (iter.hasNext()) {
            Workplace activeWorkplace = iter.next();
            if (activeWorkplace.equals(workplace)) {
                activeWorkplace.getProcess().setCurrentVisitor(visitor);
                if (visitor != null) {
                    // Делаем текущий workplace первым в списке
                    queueDataBean.getDayWorkplaceList().remove(activeWorkplace);
                    queueDataBean.getDayWorkplaceList().add(0, activeWorkplace);
                }
                break;
            }
        }

    }

    public Visitor findActiveVisitor(Visitor visitor) {
        Iterator<Visitor> iter = queueDataBean.getActiveVisitorList().iterator();
        while (iter.hasNext()) {
            Visitor activeVisitor = iter.next();
            if (activeVisitor.equals(visitor)) {
                return activeVisitor;
            }
        }
        return null;
    }

    /**
     * findVisitorByTicketNumber - возвращает очередника с любым статусом
     *
     * @param ticketNumber
     * @return
     */
    public Visitor findVisitorByTicketNumber(String ticketNumber) {
        Iterator<Visitor> iter = queueDataBean.getActiveVisitorList().iterator();
        return findVisitorByNormalizeTicketNumber(iter, ticketNumber);
    }

    /**
     * findVisitorByTicketNumber - возвращает очередника с любым статусом
     *
     * @param ticketNumber
     * @param visitorList  - список очередников, в котором осуществлять поиск
     * @return
     */
    public Visitor findVisitorByTicketNumber(String ticketNumber, List<Visitor> visitorList) {
        Iterator<Visitor> iter = visitorList.iterator();
        return findVisitorByNormalizeTicketNumber(iter, ticketNumber);
    }

    /**
     * Получить список активных рабочих окружений
     *
     * @return
     */
    public List<Workplace> getActiveWorkplaceList() {
        return queueDataBean.getActiveWorkplaceList();
    }

    private List<Visitor> fillGeneralHoldoverVisitorList(MultiValueMap<Workplace, Visitor> holdoverWorkplaceVisitorMultiKeyMap, Workplace activeWorkplace) {
        List<Visitor> generalHoldoverList = new ArrayList<>();
        for (Workplace workplace : holdoverWorkplaceVisitorMultiKeyMap.keySet()) {
            if (!workplace.equals(activeWorkplace)) {
                Collection<Visitor> visitors = holdoverWorkplaceVisitorMultiKeyMap.getCollection(workplace);
                for (Visitor visitor : visitors) {
                    for (WorkplaceService workplaceService : activeWorkplace.getWorkplaceServiceList()) {
                        if (visitor.getNextService().isChildOrEqual(workplaceService.getServiceBase())) {
                            generalHoldoverList.add(visitor);
                            break;
                        }
                    }
                }
//                generalHoldoverList.addAll(visitors);
            }
        }
        return generalHoldoverList;
    }

    /**
     * Обновить количество обслуженных посетителей у активного РО
     *
     * @param workplace
     * @param service
     */
    private void updateWorkplaceServedCount(Workplace workplace, Service service) {
        Workplace activeWorkplace = this.getActiveWorkplace(workplace);
        if (activeWorkplace != null) {
            activeWorkplace.getProcess().getServedCountMap().put(service, (activeWorkplace.getProcess().getServedCountMap().get(service) == null) ? 1 : activeWorkplace.getProcess().getServedCountMap().get(service) + 1);
            Workplace newWorkplace = coreBean.saveEntity(activeWorkplace);
            queueDataBean.updateDayWorkplace(newWorkplace, true);
        }
    }

    //вернуть отложенных на время посетителей в очередь из которых они были отложены
    private void returnHoldoverTimeVisitors(Calendar currentCalendar) {
        Iterator<Visitor> iter = queueDataBean.getActiveVisitorList().iterator();
        boolean reorderQueues = false;
        while (iter.hasNext()) {
            Visitor activeVisitor = iter.next();
            if (activeVisitor.getCurrentStatus() == VisitorStatus.HOLDOVER && activeVisitor.getProcess().getDelayCompleteTime() != null && activeVisitor.getProcess().getDelayCompleteTime().before(currentCalendar.getTime())) {
                activeVisitor.setNextService(activeVisitor.getProcess().getDelayServiceFrom());
                activeVisitor.getProcess().setDelayCompleteTime(null);
                activeVisitor.setCurrentStatus(VisitorStatus.STEP_COMPLETE);
                reorderQueues = true;
            }
        }
        //Скажем алгоритмам перестроить очереди
        if (reorderQueues) {
            visitorService.fire(new VisitorEvent(null, null, "", null));
        }
    }

    //уведомление о необходимости вызвать или завершить посетителя по ПЗ

    /**
     * Проверяет всех активных (внутри смены) посетителей по ПЗ на предмет вызова или завершения
     *
     * @param currentCalendar
     */
    private void checkAppointmentEvents(Calendar currentCalendar) {
        checkWorkplaceActivity(); // Проверка активности РО
        //По списку активных посетителей приходится проходить 2 раза - вначале на завершение, потом на вызов
        //Уведомляем о необходимости завершить посетителей по ПЗ
        Iterator<Visitor> iterVisitor = queueDataBean.getActiveVisitorList().iterator();
        while (iterVisitor.hasNext()) {
            Visitor activeVisitor = iterVisitor.next();
            //Попробуем завершить вызванных по ПЗ посетителей
            if (activeVisitor.isAppointmentVisitor() && activeVisitor.getCurrentStatus() == VisitorStatus.CALL_NEXT_AUTO) {
                Calendar appointmentCalendar;
                for (Appointment appointment : activeVisitor.getAppointmentList()) {
                    if (appointment.getDatetimeFrom() != null) {
                        appointmentCalendar = getInstance();
                        appointmentCalendar.setTime(appointment.getDatetimeTo());
                        if (appointment.getService().equals(activeVisitor.getNextService()) && currentCalendar.get(Calendar.HOUR_OF_DAY) == appointmentCalendar.get(Calendar.HOUR_OF_DAY)
                                && currentCalendar.get(Calendar.MINUTE) == appointmentCalendar.get(Calendar.MINUTE)) {
                            tryAppointmentVisitorComplete(appointment, activeVisitor.getCurrentWorkplace());
                            break;
                        }
                    }
                }
            }
        }
        //Уведомляем о необходимости вызвать посетителей по ПЗ
        iterVisitor = queueDataBean.getActiveVisitorList().iterator();
        while (iterVisitor.hasNext()) {
            Visitor activeVisitor = iterVisitor.next();
            if (activeVisitor.isAppointmentVisitor()
                    && activeVisitor.getCurrentStatus() != VisitorStatus.HOLDOVER
                    && !activeVisitor.getCurrentStatus().isCalledStatus()) {
                Appointment appointment = activeVisitor.getCurrentAppointment();

                if (appointment != null
                        && appointment.getDatetimeFrom() != null
                        && appointmentBean.getTimeSlot(appointment) != null) {
                    //Проверим просрочку ПЗ
                    if ((appointment.getAppointmentStatus() == AppointmentStatus.ACTIVE || appointment.getAppointmentStatus() == AppointmentStatus.CONFIRMED) &&
                            appointment.getDatetimeTo().before(new Date())) {
                        appointment.setAppointmentStatus(AppointmentStatus.OVERDUE);
                    }
                    tryAppointmentVisitorCall(activeVisitor, activeVisitor.getCurrentWorkplace());
                    break;
                }
            }
        }
        if (applicationState.isAppointmentFreeWorkplaceCall()) {
            iterVisitor = queueDataBean.getActiveVisitorList().iterator();
            while (iterVisitor.hasNext()) {
                Visitor activeVisitor = iterVisitor.next();
                if (activeVisitor.isAppointmentVisitor()
                        && activeVisitor.getCurrentStatus() != VisitorStatus.HOLDOVER
                        && !activeVisitor.getCurrentStatus().isCalledStatus()) {
                    Appointment appointment = activeVisitor.getLostAppointment();
                    if (appointment != null
                            && appointmentBean.getTimeSlot(appointment) != null) {
                        tryAppointmentVisitorCall(activeVisitor, activeVisitor.getCurrentWorkplace());
                        break;
                    }
                }
            }
        }
    }

    /*
    Проверка активности РО
    */
    private void checkWorkplaceActivity() {
        Date nowDate = new Date();
        int deltaInterval = 215000;
        if (lastCheckAppointmentDate != null && nowDate.getTime() - lastCheckAppointmentDate.getTime() <= deltaInterval + 60000) {
            for (Workplace workplace : queueDataBean.getActiveWorkplaceList()) {
                if (!workplace.getType().equals(WorkplaceType.AUTOMATIC) && workplace.getProcess().getLastActiveTime() != null && (nowDate.getTime() - workplace.getProcess().getLastActiveTime().getTime() > deltaInterval)) {
                    unregisterWorkplace(workplace);
                    logger.log(Level.WARNING, "Оператор с workpace {0} вышел из системы, посредством закрытия браузера ", new Object[]{workplace.getName()});
                }
            }
        }
        lastCheckAppointmentDate = nowDate; // Время последнего вызова метода
    }

    /**
     * Пытаемся вызвать посетителя по ПЗ
     *
     * @param visitor
     * @param appointmentWorkplace
     */
    private void tryAppointmentVisitorCall(Visitor visitor, Workplace appointmentWorkplace) {
        Workplace activeWorkplace = getActiveWorkplace(appointmentWorkplace);
        if (!applicationState.isAppointmentFreeWorkplaceCall() && activeWorkplace == null) {
            logger.log(Level.WARNING, "onAppointmentEvent: рабочее окружение {0} для посетителя {1} c id={2} не запущено", new Object[]{appointmentWorkplace.getName(), visitor.getVisitorInfo().toString(), visitor.getId()});
            return;
        }
        if (!applicationState.isAppointmentFreeWorkplaceCall()) {
            if (activeWorkplace.getProcess().getCurrentVisitor() != null) {
                activeWorkplace.getProcess().getAlgorithmBean().setPrevVisitor(activeWorkplace.getProcess().getCurrentVisitor());
                completeReceptionVisitor(activeWorkplace.getProcess().getCurrentVisitor());
            }
            callNextVisitor(activeWorkplace, visitor, VisitorStatus.CALL_NEXT_AUTO);
        } else {
            activeWorkplace = null;
            List<Workplace> freeWorkplaceList = new ArrayList<>();
            //попробуем найти другое свободное РО, оказывающеее эту услугу
            Iterator<Workplace> iterWorkplace = queueDataBean.getActiveWorkplaceList().iterator();
            while (iterWorkplace.hasNext()) {
                Workplace workplace = iterWorkplace.next();
                if (workplace.getProcess().getCurrentVisitor() == null) {
                    for (WorkplaceService workplaceService : workplace.getWorkplaceServicesNotRemoved()) {
                        if (visitor.getNextService().isChildOrEqual(workplaceService.getServiceBase())
                                && workplaceService.getMaintenanceMode() != MaintenanceMode.LIVE) {
                            freeWorkplaceList.add(workplace);
                        }
                    }
                }
            }
            if (!freeWorkplaceList.isEmpty()) {
                int randomPos = (int) (freeWorkplaceList.size() * Math.random());
                activeWorkplace = freeWorkplaceList.get(randomPos);
            }

            //Нашли свободное окно, оказывающее эту услугу
            if (activeWorkplace != null) {
                callNextVisitor(activeWorkplace, visitor, VisitorStatus.CALL_NEXT_AUTO);
            }
        }
    }

    /**
     * Пытаемся завершить посетителя по ПЗ
     *
     * @param appointment
     * @param appointmentWorkplace
     */
    private void tryAppointmentVisitorComplete(Appointment appointment, Workplace appointmentWorkplace) {
        Workplace activeWorkplace = getActiveWorkplace(appointmentWorkplace);
        if (!applicationState.isAppointmentFreeWorkplaceCall() && activeWorkplace == null) {
            logger.log(Level.WARNING, "onAppointmentEvent: рабочее окружение {0} для посетителя {1} c id={2} не запущено", new Object[]{appointmentWorkplace.getName(), appointment.getVisitor().getVisitorInfo().toString(), appointment.getVisitor().getId()});
            return;
        }
        //Только в том случае, если отключен свободный вызов и окно совпадает с окном в ПЗ
        if (!applicationState.isAppointmentFreeWorkplaceCall()) {
            //Если это тот же самый посетитель и он вызван автоматически
            if (activeWorkplace.getProcess().getCurrentVisitor() != null && activeWorkplace.getProcess().getCurrentVisitor().equals(appointment.getVisitor())
                    && activeWorkplace.getProcess().getCurrentVisitor().getCurrentStatus() == VisitorStatus.CALL_NEXT_AUTO) {
                activeWorkplace.getProcess().getAlgorithmBean().setPrevVisitor(activeWorkplace.getProcess().getCurrentVisitor());
                appointment.setAppointmentStatus(AppointmentStatus.COMPLETED);
                completeReceptionVisitor(activeWorkplace.getProcess().getCurrentVisitor());
            }
        }
    }

    /**
     * Подсчитать количество посетителей до текущего вообще, в комнате или в
     * окне в зависимости от текущих настроек
     *
     * @param visitor
     * @param desiredWorkplace
     * @return
     */
    private int[] calculateCountBefore(Visitor visitor, Workplace desiredWorkplace) {
        int countBefore = 0;
        int timeMinuteBefore = 0;
        int[] result = new int[2];
        int workplaceByServiceCount = 0; // Количество РО, оказывающих данную услугу в зале

        switch (applicationState.getLiveQueueDistributionType()) {
            case BY_ROOM:
                for (Workplace workplace : queueDataBean.getCurrentScheduleWorkplaceList()) {
                    if (workplace.getRoom().equals(visitor.getDesiredRoom())) {
                        for (WorkplaceService ws : workplace.getWorkplaceServiceList()) {
                            if (visitor.getNextService().isChildOrEqual(ws.getServiceBase())) {
                                workplaceByServiceCount++;
                                break;
                            }
                        }
                    }
                }
                break;
        }

        Iterator<Visitor> iter = queueDataBean.getActiveVisitorList().iterator();
        while (iter.hasNext()) {
            Visitor activeVisitor = iter.next();
            //Вызванные нам не нужны
            if (activeVisitor.getCurrentStatus().isCalledStatus()
                    || activeVisitor.getCurrentStatus() == VisitorStatus.HOLDOVER) {
                continue;
            }
            if (activeVisitor.getCurrentWorkplace() == null) {
                switch (applicationState.getLiveQueueDistributionType()) {
                    case DEFAULT:
                        //Упрощаем - пока считаем всех визиторах, а не в окнах оказываюих эту услугу
                        countBefore++;
                        timeMinuteBefore += visitor.getRoutineTimeMinute();
                        break;
                    case BY_ROOM:
                        if (activeVisitor.getDesiredRoom() == null || activeVisitor.getDesiredRoom().equals(visitor.getDesiredRoom())) {
                            countBefore++;
                            if (workplaceByServiceCount != 0) {
                                timeMinuteBefore = countBefore * visitor.getRoutineTimeMinute() / workplaceByServiceCount;
                            }
                        }
                        break;
                    case BY_WORKSTATION:
                        if (activeVisitor.getDesiredWorkplace() == null || activeVisitor.getDesiredWorkplace().equals(desiredWorkplace)) {
                            countBefore++;
                            timeMinuteBefore += visitor.getRoutineTimeMinute();
                        }
                        break;
                }
            } else {
                switch (applicationState.getLiveQueueDistributionType()) {
                    case DEFAULT:
                        //Упрощаем - пока считаем всех визиторах, а не в окнах оказываюих эту услугу
                        countBefore++;
                        timeMinuteBefore += visitor.getRoutineTimeMinute();
                        break;
                    case BY_ROOM:
                        if (activeVisitor.getCurrentWorkplace() != null && activeVisitor.getCurrentWorkplace() != null && activeVisitor.getCurrentWorkplace().getRoom().equals(visitor.getDesiredRoom())) {
                            countBefore++;
                            if (workplaceByServiceCount != 0) {
                                timeMinuteBefore = countBefore * visitor.getRoutineTimeMinute() / workplaceByServiceCount;
                            }
                        }
                        break;
                    case BY_WORKSTATION:
                        if (activeVisitor.getCurrentWorkplace() != null && activeVisitor.getCurrentWorkplace().equals(desiredWorkplace)) {
                            countBefore++;
                            timeMinuteBefore += visitor.getRoutineTimeMinute();
                        }
                        break;
                }
            }
        }
        result[0] = countBefore;
        result[1] = timeMinuteBefore;
        return result;
    }

    /**
     * Сгенерировать водяной знак для текущей смены
     */
    private void generateWaterMark(Schedule currentSchedule) {
        Date nowDate = new Date();
        Date scheduleDateFrom = currentSchedule.getBeginDate(nowDate);
        Date scheduleDateTo = currentSchedule.getEndDate(nowDate);

        FactOperation lastFactOperation = coreBean.findLastGenerateWatermarkOperation();
        if (lastFactOperation == null
                || lastFactOperation.getOperationDate().before(scheduleDateFrom)
                || lastFactOperation.getOperationDate().after(scheduleDateTo)) {
            byte[] waterMarkBytes = new byte[3];
            waterMarkBytes[0] = (byte) (0x21 + random.nextInt(0xFF - 0x21));
            waterMarkBytes[1] = (byte) (0x21 + random.nextInt(0xFF - 0x21));
            waterMarkBytes[2] = (byte) (0x21 + random.nextInt(0xFF - 0x21));
            String dayWaterMark = "";
            try {
                dayWaterMark = new String(waterMarkBytes, "CP1251");
            } catch (UnsupportedEncodingException ex) {
                logger.log(Level.SEVERE, ex.getMessage());
            }
            coreBean.saveSystemSetting(Setting.DAY_WATERMARK_PROPERTY, dayWaterMark);
        }
    }


    /**
     * Если для заданного рабочего окружения на текущую смену указан флаг автоматического обслуживания и
     * сгенерирован хотя бы один слот, то запускаем автоматическое окно
     * (регистрируем пустого пользователя)
     *
     * @param workplace
     */
    private void addDayAndInitWorkplace(Workplace workplace) {
        if (workplace.getType().equals(WorkplaceType.AUTOMATIC) && workplace.getCurrentSchedule() != null) {
            registerEmployee(null, workplace);
            logger.log(Level.INFO, "Запущено автоматическое обслуживание для размещения: {0}, рабочее окружение: {1}", new Object[]{workplace.getName(), workplace.getName()});

        } else {
            AlgorithmBean algorithmBean = new AlgorithmBean(workplace.getAlgorithm(), workplace);
            workplace.getProcess().setAlgorithmBean(algorithmBean);
            queueDataBean.getDayWorkplaceList().add(workplace);
        }
    }

    private void calculateServedCount() {
        // Находим завершенных посетителей по фактическим операциям за текущую смену и складываем количество в Мапу для каждого РО
        Date dateFrom = applicationState.getLocalDepartment().getCurrentTimetable().getCurrentSchedule().getBeginDate(new Date());
        Date dateTo = applicationState.getLocalDepartment().getCurrentTimetable().getCurrentSchedule().getEndDate(new Date());
        List<FactOperation> factOperations = coreBean.findCompletedFactOperationByDate(dateFrom, dateTo);
        for (FactOperation factOperation : factOperations) {
            if (factOperation.getWorkplace() != null && factOperation.getService() != null) {
                if (queueDataBean.getDayWorkplaceList().contains(factOperation.getWorkplace())) {
                    int index = queueDataBean.getDayWorkplaceList().indexOf(factOperation.getWorkplace());
                    Workplace workplace = queueDataBean.getDayWorkplaceList().get(index);
                    if (workplace.getProcess().getServedCountMap() == null) {
                        workplace.getProcess().setServedCountMap(new HashMap<Service, Integer>());
                    }
                    workplace.getProcess().getServedCountMap().put(factOperation.getService(), (workplace.getProcess().getServedCountMap().get(factOperation.getService()) == null) ? 1 : workplace.getProcess().getServedCountMap().get(factOperation.getService()) + 1);
                }
            }

        }

    }
   
}
