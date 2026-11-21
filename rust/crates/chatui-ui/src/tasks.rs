use std::cell::RefCell;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::rc::Rc;
use std::sync::mpsc::{self, Receiver, Sender, TryRecvError};
use std::time::Duration;

#[derive(Debug, Clone, Copy)]
pub(crate) struct TaskError;

impl std::fmt::Display for TaskError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str("The operation was interrupted. Please try again.")
    }
}

trait PendingTask {
    fn poll(&mut self) -> bool;
}

type CompletionHandler<T> = Box<dyn FnOnce(Result<T, TaskError>)>;

struct Task<T, P> {
    result: Receiver<Result<T, TaskError>>,
    progress: Receiver<P>,
    on_progress: Box<dyn FnMut(P)>,
    on_finish: Option<CompletionHandler<T>>,
}

impl<T, P> PendingTask for Task<T, P> {
    fn poll(&mut self) -> bool {
        while let Ok(progress) = self.progress.try_recv() {
            (self.on_progress)(progress);
        }

        let result = match self.result.try_recv() {
            Ok(result) => result,
            Err(TryRecvError::Disconnected) => Err(TaskError),
            Err(TryRecvError::Empty) => return false,
        };

        while let Ok(progress) = self.progress.try_recv() {
            (self.on_progress)(progress);
        }
        if let Some(on_finish) = self.on_finish.take() {
            on_finish(result);
        }
        true
    }
}

type PendingTasks = Rc<RefCell<Vec<Box<dyn PendingTask>>>>;

pub(crate) struct UiTasks {
    background: bool,
    pending: PendingTasks,
    _timer: Option<slint::Timer>,
}

impl UiTasks {
    pub(crate) fn new(background: bool) -> Self {
        let pending: PendingTasks = Rc::new(RefCell::new(Vec::new()));
        let timer = background.then(|| {
            let timer = slint::Timer::default();
            let weak = Rc::downgrade(&pending);
            timer.start(
                slint::TimerMode::Repeated,
                Duration::from_millis(50),
                move || {
                    if let Some(pending) = weak.upgrade() {
                        poll_tasks(&pending);
                    }
                },
            );
            timer
        });
        Self {
            background,
            pending,
            _timer: timer,
        }
    }

    pub(crate) fn run<T: Send + 'static>(
        &self,
        work: impl FnOnce() -> T + Send + 'static,
        on_finish: impl FnOnce(Result<T, TaskError>) + 'static,
    ) {
        self.run_with_progress(move |_: Sender<()>| work(), |_| {}, on_finish);
    }

    pub(crate) fn run_with_progress<T: Send + 'static, P: Send + 'static>(
        &self,
        work: impl FnOnce(Sender<P>) -> T + Send + 'static,
        on_progress: impl FnMut(P) + 'static,
        on_finish: impl FnOnce(Result<T, TaskError>) + 'static,
    ) {
        let (result_sender, result) = mpsc::channel();
        let (progress_sender, progress) = mpsc::channel();
        let execute = move || {
            let outcome =
                catch_unwind(AssertUnwindSafe(|| work(progress_sender))).map_err(|_| TaskError);
            let _ = result_sender.send(outcome);
        };
        let mut task = Task {
            result,
            progress,
            on_progress: Box::new(on_progress),
            on_finish: Some(Box::new(on_finish)),
        };
        if self.background {
            self.pending.borrow_mut().push(Box::new(task));
            std::thread::spawn(execute);
        } else {
            execute();
            task.poll();
        }
    }

    pub(crate) fn poll(&self) {
        poll_tasks(&self.pending);
    }
}

fn poll_tasks(pending: &PendingTasks) {
    // Completion handlers may enqueue another task, so never call them while
    // the queue is borrowed.
    let tasks = std::mem::take(&mut *pending.borrow_mut());
    let waiting = tasks
        .into_iter()
        .filter_map(|mut task| (!task.poll()).then_some(task))
        .collect::<Vec<_>>();
    pending.borrow_mut().extend(waiting);
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::Cell;
    use std::time::Instant;

    #[test]
    fn blocking_work_leaves_the_ui_free_and_delivers_progress_before_completion() {
        i_slint_backend_testing::init_no_event_loop();
        let tasks = UiTasks::new(true);
        let (release, wait) = mpsc::channel();
        let steps = Rc::new(RefCell::new(Vec::new()));
        let progress = Rc::clone(&steps);
        let finished = Rc::clone(&steps);
        tasks.run_with_progress(
            move |progress| {
                progress.send("working").unwrap();
                wait.recv_timeout(Duration::from_secs(5)).unwrap();
                "finished"
            },
            move |step| progress.borrow_mut().push(step),
            move |result| finished.borrow_mut().push(result.unwrap()),
        );

        let deadline = Instant::now() + Duration::from_secs(5);
        while steps.borrow().is_empty() && Instant::now() < deadline {
            tasks.poll();
            std::thread::sleep(Duration::from_millis(1));
        }
        assert_eq!(&*steps.borrow(), &["working"]);

        release.send(()).unwrap();
        while steps.borrow().len() < 2 && Instant::now() < deadline {
            tasks.poll();
            std::thread::sleep(Duration::from_millis(1));
        }
        assert_eq!(&*steps.borrow(), &["working", "finished"]);
        tasks.poll();
        assert_eq!(steps.borrow().len(), 2);
    }

    #[test]
    fn failed_workers_complete_with_an_error_instead_of_leaving_a_busy_screen() {
        i_slint_backend_testing::init_no_event_loop();
        let tasks = UiTasks::new(false);
        let failed = Rc::new(Cell::new(false));
        let recorded = Rc::clone(&failed);
        tasks.run(
            || panic!("test worker failure"),
            move |result| recorded.set(result.is_err()),
        );
        assert!(failed.get());
    }
}
