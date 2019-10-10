//= require jquery
//= require bootstrap

$(function() {
  $('.evaluation[data-running=true]').each(function() {
    const taskId = $(this).attr("data-task-id");
    setTimeout(refreshEvaluationStatus(taskId), 3000)
  });
  $('.pending-evaluations[data-assignment-id]').each(function() {
    const assignmentId = $(this).attr("data-assignment-id");
    setTimeout(refreshAssignmentEvaluationStatus(assignmentId), 10000)
  })
});

const refreshEvaluationStatus = taskId => () => {
  $.get("/tasks/" + taskId + "/evaluation", res => {
    if (res.running) {
      setTimeout(refreshEvaluationStatus(taskId), 3000)
    } else {
      const evaluation = $('.evaluation[data-task-id=' + taskId +']');
      evaluation.find(".evaluation-running").html('<i class="fas fa-check"></i> Finished');
      evaluation.find(".evaluation-view")
        .attr('title', '')
        .attr('data-original-title', '')
        .tooltip('dispose')
        .tooltip()
        .children()
        .first()
        .attr("href", res.url)
        .removeClass("disabled");

      const message = `<p>Evaluation for task '${res.taskTitle}' has finished.</p><a href="${res.url}" class="btn btn-sm btn-light"><i class="fas fa-poll"></i> Show Results</a>`;
      showToast('Evaluation finished', 'fas fa-check text-success', message);
    }
  })
};

const refreshAssignmentEvaluationStatus = assignmentId => () => {
  $.get(`/assignments/${assignmentId}/evaluations-status`, map => {
    let pending = 0;
    Object.entries(map).forEach(([answerId, status]) => {
      const $button = $(`a[data-answer-id="${answerId}"]`);
      if (status.running) {
        pending += 1;
        $button.find('i').attr('class', 'fas fa-spinner fa-pulse');
      } else {
        $button.find('i').attr('class', 'fas fa-poll');
      }
      if (status.url) {
        $button
          .removeClass('disabled')
          .attr('href', status.url);
      }
    });
    if (pending <= 0) {
      $('.pending-evaluations[data-assignment-id]').remove();
    } else {
      $('.pending-evaluations-count').text(pending);
      setTimeout(refreshAssignmentEvaluationStatus(assignmentId), 10000);
    }
  });
};
