import {Component, Inject} from '@angular/core';
import {MAT_DIALOG_DATA, MatDialogRef} from '@angular/material/dialog';

export interface DialogConfirmation {
  header: string;
  content: string;
}

@Component({
  selector: 'app-confirmation-dialog',
  templateUrl: 'confirmation-dialog.html'
})
export class ConfirmDialogComponent {
  constructor(public dialogRef: MatDialogRef<ConfirmDialogComponent>, @Inject(MAT_DIALOG_DATA) public data: DialogConfirmation) {
  }
}
